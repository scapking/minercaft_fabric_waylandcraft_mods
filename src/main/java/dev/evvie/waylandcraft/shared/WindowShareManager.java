package dev.evvie.waylandcraft.shared;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.network.SharedWindowClientHandler;
import dev.evvie.waylandcraft.network.SharedWindowImagePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.evvie.waylandcraft.render.SharedWindowDisplay;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 窗口共享管理器（优化版）
 * 
 * 优化内容：
 * 1. 码率限速（Token Bucket）
 * 2. 自适应质量（根据带宽利用率动态调整scale）
 * 3. 像素差异检测（跳过无变化帧）
 */
public class WindowShareManager {
	
	private static final Logger LOGGER = LoggerFactory.getLogger("waylandcraft-share-manager");
	
	private final WaylandCraft clientMod;
	private final WaylandCraftCommon serverMod;
	
	private ImageCapture.CaptureConfig captureConfig;
	private final FrameRateController frameRateController;
	private final DiffUpdateManager diffUpdateManager;
	
	private final Map<Long, ShareState> shareStates = new ConcurrentHashMap<>();
	private boolean sharingEnabled = true;
	
	// === 码率限速 ===
	private long bytesSentThisSecond = 0;
	private long currentSecondStart = 0;
	
	// === 自适应质量 ===
	private float adaptiveScaleMultiplier = 1.0f; // 乘以config.scale得到实际scale
	private int adaptiveEvalCounter = 0;
	private int adaptiveOverLimitCount = 0;    // 连续超限帧数
	private int adaptiveUnderUtilCount = 0;    // 连续低利用帧数
	private static final int ADAPTIVE_EVAL_INTERVAL = 60; // 每60帧评估一次
	private static final float ADAPTIVE_SCALE_MIN = 0.1f;
	private static final float ADAPTIVE_SCALE_MAX = 1.0f;
	private static final float ADAPTIVE_SCALE_DOWN = 0.9f;  // 超限时降低10%
	private static final float ADAPTIVE_SCALE_UP = 1.1f;    // 低利用时提高10%
	
	// === 帧间统计 ===
	private long adaptiveFrameBytes = 0; // 当前评估周期内的总字节
	private boolean lastFrameOverLimit = false;
	
	// === 心跳帧 ===
	// 无内容变化时，最多间隔这么久强制发一帧，保证接收端纹理持续刷新
	private static final long HEARTBEAT_INTERVAL_MS = 2000;
	
	public WindowShareManager(WaylandCraft clientMod) {
		this.clientMod = clientMod;
		this.serverMod = null;
		// 默认全分辨率高质量（scale=1.0, quality=0.85, fps=20）：
		// 之前默认 0.5 缩放 + JPEG 0.7 导致接收端画面明显比本地模糊。
		// fps 保持 20 控制手机端解码负担；带宽不限（局域网/服务器转发通常充裕）。
		// 少部分人使用、不考虑带宽：全分辨率 + 最高 JPEG 质量，与本地共享端视觉一致。
		// maxBitrate=0（不限速）→ evaluateAdaptiveQuality 自动禁用，quality 永不降。
		this.captureConfig = new ImageCapture.CaptureConfig(1.0f, 1.0f, 20);
		this.frameRateController = new FrameRateController();
		this.diffUpdateManager = new DiffUpdateManager();
		
		registerClientEvents();
		LOGGER.info("WindowShareManager initialized (client)");
	}
	
	public WindowShareManager(WaylandCraftCommon serverMod) {
		this.clientMod = null;
		this.serverMod = serverMod;
		this.captureConfig = null;
		this.frameRateController = new FrameRateController();
		this.diffUpdateManager = new DiffUpdateManager();
		
		LOGGER.info("WindowShareManager initialized (server)");
	}
	
	private void registerClientEvents() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			handleDisconnect();
		});
	}
	
	public boolean startSharing(long windowHandle, String windowTitle) {
		if(clientMod == null) {
			LOGGER.warn("Cannot start sharing on server side");
			return false;
		}
		
		if(shareStates.containsKey(windowHandle)) {
			LOGGER.warn("Window 0x{} is already being shared", Long.toHexString(windowHandle));
			return false;
		}
		
		ShareState state = new ShareState(windowHandle, windowTitle);
		shareStates.put(windowHandle, state);
		
		SharedWindowClientHandler.requestWindowRegister(windowHandle, windowTitle);
		
		LOGGER.info("Started sharing window 0x{}: {}", Long.toHexString(windowHandle), windowTitle);
		return true;
	}
	
	public boolean stopSharing(long windowHandle) {
		ShareState state = shareStates.remove(windowHandle);
		if(state == null) {
			LOGGER.warn("Window 0x{} is not being shared", Long.toHexString(windowHandle));
			return false;
		}
		
		SharedWindowClientHandler.requestWindowUnregister(windowHandle);
		
		diffUpdateManager.clearWindow(windowHandle);
		frameRateController.reset(windowHandle);
		ImageCapture.clearDiffCache(windowHandle);
		ImageCapture.cleanupPbo(windowHandle);
		ImageCapture.cleanupScaleFbo(windowHandle);
		
		LOGGER.info("Stopped sharing window 0x{}", Long.toHexString(windowHandle));
		return true;
	}
	
	public void update() {
		if(clientMod == null || !sharingEnabled) return;
		
		// 重置每秒码率计数器
		long now = System.currentTimeMillis();
		if(now - currentSecondStart > 1000) {
			bytesSentThisSecond = 0;
			currentSecondStart = now;
		}
		
		for(ShareState state : shareStates.values()) {
			updateSharedWindow(state);
		}
	}
	
	/**
	 * 更新单个共享窗口（带全部优化）
	 */
	private void updateSharedWindow(ShareState state) {
		ImageCapture.CaptureConfig effectiveConfig = state.getEffectiveConfig(captureConfig);
		
		// 帧率限制
		if(!frameRateController.shouldUpdate(state.windowHandle, effectiveConfig.maxFps)) {
			return;
		}
		
		// 获取本地窗口
		WLCToplevel toplevel = getLocalWindow(state.windowHandle);
		if(toplevel == null || !toplevel.isMapped() || toplevel.framebuffer == null) {
			return;
		}
		
		// 计算实际使用的scale（自适应 × 配置）
		float effectiveScale = effectiveConfig.scale * adaptiveScaleMultiplier;
		effectiveScale = Math.max(0.1f, Math.min(1.0f, effectiveScale));
		
		// === 像素差异检测 ===
		if(effectiveConfig.diffUpdate) {
			byte[] rawFrame = ImageCapture.captureFromFramebufferRaw(state.windowHandle, toplevel.framebuffer, effectiveScale);
			if(rawFrame != null) {
				if(!ImageCapture.hasSignificantChange(state.windowHandle, rawFrame, effectiveConfig.diffThreshold)) {
					// 无显著变化，跳过本帧
					state.skippedFrames++;
					// 心跳帧：长时间无变化时也强制发一帧，避免接收端纹理一直不刷新
					// （也兜底 diff 基准帧因某种原因失效导致的永久静默）
					long nowMs = System.currentTimeMillis();
					if(nowMs - state.lastFrameSentTime > HEARTBEAT_INTERVAL_MS) {
						state.skippedFrames = 0;
					} else {
						return;
					}
				}
			}
			// rawFrame == null（捕获失败）：不跳过，继续走完整 JPEG 路径发送
		}
		
		// === 捕获（使用优化的PBO+GPU缩放+直接编码路径，PBO/FBO 按窗口隔离） ===
		byte[] imageData = ImageCapture.captureFromFramebuffer(
			state.windowHandle,
			toplevel.framebuffer,
			effectiveScale,
			effectiveConfig.quality
		);
		
		if(imageData == null) {
			return;
		}
		
		// === 码率限速 ===
		if(effectiveConfig.maxBitrate > 0) {
			long maxBytesPerSecond = (long)effectiveConfig.maxBitrate * 1000 / 8; // kbps → bytes/sec
			if(bytesSentThisSecond + imageData.length > maxBytesPerSecond) {
				// 超过码率限制，跳过本帧
				state.rateLimitedFrames++;
				lastFrameOverLimit = true;
				adaptiveOverLimitCount++;
				adaptiveUnderUtilCount = 0;
				return;
			}
			lastFrameOverLimit = false;
		}
		
		// === 自适应质量评估 ===
		adaptiveEvalCounter++;
		adaptiveFrameBytes += imageData.length;
		
		if(adaptiveEvalCounter >= ADAPTIVE_EVAL_INTERVAL) {
			evaluateAdaptiveQuality(effectiveConfig);
			adaptiveEvalCounter = 0;
			adaptiveFrameBytes = 0;
		}
		
		// 处理差分更新（当前已禁用，JPEG压缩数据diff无意义）
		byte[] processedData = diffUpdateManager.processFrame(state.windowHandle, imageData);
		if(processedData == null) return;
		
		// 使用 framebuffer 原始尺寸（非缩放），接收端根据这个尺寸计算世界大小。
		// 注意：必须是 framebuffer 尺寸（含 xoff/yoff 偏移的完整缓冲），
		// 而不是 geometry 尺寸 —— 否则接收端四边形尺寸与纹理内容对不上，窗口会偏移/缩小。
		int originalW = toplevel.framebuffer.getWidth();
		int originalH = toplevel.framebuffer.getHeight();
		// xoff/yoff 随帧传递，接收端用于 bufOffset 对齐（与本地 WindowDisplay.render 一致）
		int framebufferXOff = toplevel.framebuffer.getXOff();
		int framebufferYOff = toplevel.framebuffer.getYOff();
		
		// 从本地WindowDisplay获取窗口变换
		double pivotX = 0, pivotY = 0, pivotZ = 0;
		double normalX = 0, normalY = 0, normalZ = 1;
		double downX = 0, downY = -1, downZ = 0;
		double viewScale = 1.0;
		int geometryWidth = toplevel.geometry.width();
		int geometryHeight = toplevel.geometry.height();
		if(clientMod != null) {
			for(var display : clientMod.displays) {
				if(display.window.getHandle() == state.windowHandle) {
					Vec3 pivot = display.pivot;
					Vec3 normal = display.normal();
					Vec3 d = display.down();
					pivotX = pivot.x; pivotY = pivot.y; pivotZ = pivot.z;
					normalX = normal.x; normalY = normal.y; normalZ = normal.z;
					downX = d.x; downY = d.y; downZ = d.z;
					// 视觉缩放倍数必须同步，否则接收端尺寸与本地不一致
					viewScale = display.viewScale;
					break;
				}
			}
		}
		
		// 发送端自己的 pixelsPerBlock：接收端用它渲染，保证世界尺寸与本地一致
		// （接收端可能使用不同的 PPB 设置或 native 不可用导致 settings==null）
		int senderPixelsPerBlock = 500;
		if(WaylandCraft.instance != null && WaylandCraft.instance.settings != null) {
			senderPixelsPerBlock = WaylandCraft.instance.settings.getPixelsPerBlock();
		}
		
		SharedWindowImagePayload imagePayload = new SharedWindowImagePayload(
			state.windowHandle, 0, framebufferXOff, framebufferYOff,
			originalW, originalH,
			processedData,
			pivotX, pivotY, pivotZ,
			normalX, normalY, normalZ,
			downX, downY, downZ,
			viewScale, geometryWidth, geometryHeight,
			senderPixelsPerBlock
		);
		ClientPlayNetworking.send(imagePayload);
		
		// 更新统计
		bytesSentThisSecond += processedData.length;
		state.lastUpdateTime = System.currentTimeMillis();
		state.lastFrameSentTime = state.lastUpdateTime;
		state.frameCount++;
		state.totalBytes += processedData.length;
		state.currentFps = frameRateController.getCurrentFps(state.windowHandle);
		state.currentBitrate = bytesSentThisSecond * 8 / 1000; // kbps
	}
	
	/**
	 * 评估自适应质量
	 * 超限 → 降低scale，低利用 → 提高scale
	 */
	private void evaluateAdaptiveQuality(ImageCapture.CaptureConfig config) {
		if(config.maxBitrate <= 0) return; // 无码率限制时不做自适应
		
		long maxBytesPerSecond = (long)config.maxBitrate * 1000 / 8;
		float utilization = maxBytesPerSecond > 0 ? (float)adaptiveFrameBytes / (maxBytesPerSecond * ADAPTIVE_EVAL_INTERVAL / 20) : 0;
		
		if(adaptiveOverLimitCount >= ADAPTIVE_EVAL_INTERVAL / 2) {
			// 超过一半帧数都超限 → 降低质量
			adaptiveScaleMultiplier = Math.max(ADAPTIVE_SCALE_MIN, adaptiveScaleMultiplier * ADAPTIVE_SCALE_DOWN);
			LOGGER.info("[ADAPTIVE] Scale decreased to {} (over limit: {} frames)", String.format("%.2f", adaptiveScaleMultiplier), adaptiveOverLimitCount);
			adaptiveOverLimitCount = 0;
		} else if(utilization < 0.5f && adaptiveScaleMultiplier < ADAPTIVE_SCALE_MAX) {
			// 利用率低于50% → 提高质量
			adaptiveScaleMultiplier = Math.min(ADAPTIVE_SCALE_MAX, adaptiveScaleMultiplier * ADAPTIVE_SCALE_UP);
			LOGGER.info("[ADAPTIVE] Scale increased to {} (utilization: {}%)", String.format("%.2f", adaptiveScaleMultiplier), String.format("%.1f", utilization * 100));
			adaptiveUnderUtilCount = 0;
		}
	}
	
	@Nullable
	private WLCToplevel getLocalWindow(long windowHandle) {
		if(clientMod == null || clientMod.bridge == null) {
			return null;
		}
		return clientMod.bridge.getToplevel(windowHandle);
	}
	
	private void handleDisconnect() {
		shareStates.clear();
		diffUpdateManager.clear();
		frameRateController.clear();
		adaptiveScaleMultiplier = 1.0f;
		bytesSentThisSecond = 0;
		ImageCapture.clearAllDiffCaches();
		ImageCapture.cleanupAllWindowResources();
		LOGGER.info("Cleared all share states due to disconnect");
	}
	
	@Nullable
	public ShareState getShareState(long windowHandle) {
		return shareStates.get(windowHandle);
	}
	
	public Map<Long, ShareState> getAllShareStates() {
		return Map.copyOf(shareStates);
	}
	
	public void setCaptureConfig(ImageCapture.CaptureConfig config) {
		this.captureConfig = config;
		LOGGER.info("Updated capture config: {}", config.getSummary());
	}
	
	public void setPerWindowConfig(long windowHandle, ImageCapture.CaptureConfig config) {
		ShareState state = shareStates.get(windowHandle);
		if(state == null) {
			LOGGER.warn("Cannot set per-window config: window 0x{} not shared", Long.toHexString(windowHandle));
			return;
		}
		state.perWindowConfig = config;
		LOGGER.info("Set per-window config for 0x{}: {}", Long.toHexString(windowHandle), config.getSummary());
	}
	
	public void clearPerWindowConfig(long windowHandle) {
		ShareState state = shareStates.get(windowHandle);
		if(state != null) {
			state.perWindowConfig = null;
			LOGGER.info("Cleared per-window config for 0x{}", Long.toHexString(windowHandle));
		}
	}
	
	public void setSharingEnabled(boolean enabled) {
		this.sharingEnabled = enabled;
		LOGGER.info("Sharing {}", enabled ? "enabled" : "disabled");
	}
	
	public boolean isSharingEnabled() {
		return sharingEnabled;
	}
	
	/**
	 * 获取自适应缩放乘数（供stats命令使用）
	 */
	public float getAdaptiveScaleMultiplier() {
		return adaptiveScaleMultiplier;
	}
	
	/**
	 * 获取当前码率利用率（0.0-1.0+）
	 */
	public float getBitrateUtilization() {
		if(captureConfig == null || captureConfig.maxBitrate <= 0) return 0;
		long maxBytesPerSecond = (long)captureConfig.maxBitrate * 1000 / 8;
		return maxBytesPerSecond > 0 ? (float)bytesSentThisSecond / maxBytesPerSecond : 0;
	}
	
	public String getStats() {
		long totalFrames = shareStates.values().stream().mapToLong(s -> s.frameCount).sum();
		long totalBytes = shareStates.values().stream().mapToLong(s -> s.totalBytes).sum();
		long totalSkipped = shareStates.values().stream().mapToLong(s -> s.skippedFrames).sum();
		long totalRateLimited = shareStates.values().stream().mapToLong(s -> s.rateLimitedFrames).sum();
		
		return String.format("Windows: %d, Frames: %d, Skipped: %d, RateLimited: %d, Bytes: %d, Adaptive: %.2f, Utilization: %.1f%%", 
			shareStates.size(), totalFrames, totalSkipped, totalRateLimited, totalBytes,
			adaptiveScaleMultiplier, getBitrateUtilization() * 100);
	}
	
	/**
	 * 共享状态
	 */
	public static class ShareState {
		public final long windowHandle;
		public final String windowTitle;
		public final long startTime;
		
		public long lastUpdateTime = 0;
		public long lastFrameSentTime = 0;   // 最近一次实际发送帧的时间（心跳帧用）
		public long frameCount = 0;
		public long totalBytes = 0;
		public long skippedFrames = 0;      // diff检测跳过的帧数
		public long rateLimitedFrames = 0;   // 码率限制跳过的帧数
		public int currentFps = 0;           // 当前实际帧率
		public long currentBitrate = 0;      // 当前码率 (kbps)
		
		public ImageCapture.CaptureConfig perWindowConfig = null;
		
		public ShareState(long windowHandle, String windowTitle) {
			this.windowHandle = windowHandle;
			this.windowTitle = windowTitle;
			this.startTime = System.currentTimeMillis();
		}
		
		public ImageCapture.CaptureConfig getEffectiveConfig(ImageCapture.CaptureConfig globalConfig) {
			return perWindowConfig != null ? perWindowConfig : globalConfig;
		}
	}
}
