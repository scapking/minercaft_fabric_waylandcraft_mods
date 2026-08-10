package dev.evvie.waylandcraft.shared;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * 远程窗口渲染器（异步解码版）
 * 
 * JPEG 路径架构：
 *   updateTexture() [任意线程] ──入队──▶ 单线程解码 executor（工作线程：JPEG→ARGB）
 *     ──▶ 每窗口解码结果队列（容量 2，满则丢最旧）──▶ 客户端 tick（渲染线程）poll → GL 上传
 * 
 * 关键约束：
 * 1. 解码线程绝不碰 GL：createTexture/destroyTexture/upload 只允许在客户端（渲染）线程执行
 * 2. 每窗口一个待解码槽位，新帧覆盖旧帧 = 丢中间帧保实时
 * 3. updateTextureRGBA（Portal 本地捕获）仍为同步路径，调用方必须保证在渲染线程
 */
public class RemoteWindowRenderer {
	
	private static final Logger LOGGER = LoggerFactory.getLogger("waylandcraft-remote-renderer");
	
	private final Map<Long, TextureEntry> textureCache = new ConcurrentHashMap<>();
	
	private static final int MAX_CACHED_TEXTURES = 50;
	private static final int CLEANUP_INTERVAL = 600;
	private int tickCounter = 0;
	
	// === 异步 JPEG 解码状态 ===
	/** 每窗口一个待解码槽位（新帧覆盖旧帧 = 丢中间帧） */
	private final Map<Long, PendingFrame> pendingByWindow = new ConcurrentHashMap<>();
	/** 每窗口解码结果队列（容量 DECODED_QUEUE_CAPACITY，满则丢最旧） */
	private final Map<Long, ArrayDeque<DecodedFrame>> decodedByWindow = new ConcurrentHashMap<>();
	private static final int DECODED_QUEUE_CAPACITY = 2;
	
	private final Object executorLock = new Object();
	private ExecutorService decodeExecutor;
	
	private static final AtomicBoolean TICK_HOOK_REGISTERED = new AtomicBoolean(false);
	
	public RemoteWindowRenderer() {
		LOGGER.info("RemoteWindowRenderer initialized");
		// 客户端 tick 运行在渲染/GL 线程：在这里 poll 解码结果并上传
		if(TICK_HOOK_REGISTERED.compareAndSet(false, true)) {
			ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
		}
	}
	
	private Identifier getTextureLocation(long windowHandle) {
		return Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "remote_" + windowHandle);
	}
	
	/**
	 * 更新远程窗口纹理（JPEG 路径：异步解码）
	 * 
	 * 只把 jpegData 放入该窗口的待解码槽位（新帧覆盖旧帧 = 丢中间帧）后立即返回，
	 * 不阻塞调用线程。真正的 JPEG 解码在后台工作线程完成，渲染线程在客户端 tick 时
	 * poll 解码结果做 GL 上传（见 flushPendingUploads）。
	 */
	public void updateTexture(long windowHandle, int x, int y, int width, int height, byte[] jpegData) {
		if(jpegData == null || jpegData.length == 0) return;
		
		PendingFrame slot = pendingByWindow.computeIfAbsent(windowHandle, h -> new PendingFrame());
		slot.publish(x, y, width, height, jpegData);
		
		// 该窗口没有在途解码任务 → 提交一个；否则由在途任务的 finally 兜底续处理
		if(slot.inFlight.compareAndSet(false, true)) {
			submitDecode(windowHandle, slot);
		}
	}
	
	/**
	 * 直接上传 RGBA 像素（用于 Portal ScreenCast 原始帧，不经过 JPEG 解码）
	 * 字节顺序为 R,G,B,A（与 NativeImage Format.RGBA 内存布局一致，直接写入）
	 */
	public void updateTextureRGBA(long windowHandle, int width, int height, byte[] rgbaData) {
		if(width <= 0 || height <= 0 || rgbaData == null) return;
		if(rgbaData.length < width * height * 4) {
			LOGGER.warn("RGBA data too small: {} bytes for {}x{}", rgbaData.length, width, height);
			return;
		}
		
		TextureEntry entry = textureCache.get(windowHandle);
		if(entry == null || entry.width != width || entry.height != height) {
			destroyTexture(windowHandle);
			entry = createTexture(windowHandle, width, height);
			if(entry == null) return;
		}
		
		try {
			NativeImage nativeImage = entry.texture.getPixels();
			if(nativeImage == null || nativeImage.isClosed()) {
				destroyTexture(windowHandle);
				entry = createTexture(windowHandle, width, height);
				if(entry == null) return;
				nativeImage = entry.texture.getPixels();
				if(nativeImage == null) return;
			}
			
			long ptr = nativeImage.getPointer();
			if(ptr == 0L) {
				LOGGER.warn("NativeImage pointer is null for window 0x{}", Long.toHexString(windowHandle));
				return;
			}
			
			// NativeImage Format.RGBA 内存布局 = [R,G,B,A]（byte0 = R）
			// Portal 帧字节序已是 R,G,B,A → 直接 memPutInt（little-endian 先写 R）
			long offset = ptr;
			for(int i = 0; i < width * height; i++) {
				int base = i * 4;
				int r = rgbaData[base] & 0xFF;
				int g = rgbaData[base + 1] & 0xFF;
				int b = rgbaData[base + 2] & 0xFF;
				int a = rgbaData[base + 3] & 0xFF;
				MemoryUtil.memPutInt(offset, (a << 24) | (b << 16) | (g << 8) | r);
				offset += 4;
			}
			
			entry.texture.upload();
			entry.lastUpdate = System.currentTimeMillis();
		} catch(Exception e) {
			LOGGER.error("Failed to update RGBA texture for window 0x{}", Long.toHexString(windowHandle), e);
		}
	}
	
	/**
	 * 提交一个解码任务到工作线程（executor 被 clear() 关闭时重建一次再试）
	 */
	private void submitDecode(long windowHandle, PendingFrame slot) {
		try {
			ensureExecutor().submit(() -> decodeWindow(windowHandle, slot));
		} catch(RejectedExecutionException e) {
			try {
				ensureExecutor().submit(() -> decodeWindow(windowHandle, slot));
			} catch(RejectedExecutionException e2) {
				LOGGER.warn("Failed to submit JPEG decode for window 0x{}", Long.toHexString(windowHandle));
			}
		}
	}
	
	/**
	 * 工作线程：解码该窗口槽位里最新一帧 JPEG → ARGB，结果放入解码结果队列。
	 * 每次任务只处理一帧（有界工作量），结束后若又有新帧到达则重新提交，
	 * 让多个窗口在单线程 executor 上公平轮转、互不饿死。全程不碰 GL。
	 */
	private void decodeWindow(long windowHandle, PendingFrame slot) {
		try {
			PendingFrame.Job job = slot.take();
			if(job == null) return;
			
			int[] argbPixels;
			int decodedW, decodedH;
			try {
				BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(job.jpegData));
				if(bufferedImage == null) {
					LOGGER.warn("ImageIO.read returned null ({} bytes)", job.jpegData.length);
					return;
				}
				decodedW = bufferedImage.getWidth();
				decodedH = bufferedImage.getHeight();
				// 批量获取所有像素（1次JNI调用）
				argbPixels = bufferedImage.getRGB(0, 0, decodedW, decodedH, null, 0, decodedW);
			} catch(Exception e) {
				LOGGER.error("Failed to decode JPEG ({} bytes) on worker thread", job.jpegData.length, e);
				return;
			}
			
			offerDecoded(windowHandle, slot, new DecodedFrame(windowHandle, job.x, job.y, decodedW, decodedH, argbPixels));
		} finally {
			// 只有槽位仍是同一个（未被 destroyTexture/重建）才续处理
			if(slot == pendingByWindow.get(windowHandle)) {
				slot.inFlight.set(false);
				if(slot.hasPending() && slot.inFlight.compareAndSet(false, true)) {
					submitDecode(windowHandle, slot);
				}
			}
		}
	}
	
	/**
	 * 把解码结果放入该窗口的结果队列；窗口已注销/重建则丢弃。
	 * 队列满（容量 DECODED_QUEUE_CAPACITY）时丢最旧帧。
	 */
	private void offerDecoded(long windowHandle, PendingFrame slot, DecodedFrame frame) {
		if(pendingByWindow.get(windowHandle) != slot) return;
		
		ArrayDeque<DecodedFrame> queue = decodedByWindow.computeIfAbsent(windowHandle, h -> new ArrayDeque<>());
		synchronized(queue) {
			if(queue.size() >= DECODED_QUEUE_CAPACITY) {
				queue.poll(); // 丢最旧
			}
			queue.offer(frame);
		}
	}
	
	/**
	 * 渲染线程：poll 每窗口最新的解码结果并做 GL 上传（必须在 GL/客户端线程调用）。
	 * 只上传最新一帧，其余丢弃（丢中间帧保实时）。
	 */
	private void flushPendingUploads() {
		if(decodedByWindow.isEmpty()) return;
		for(long windowHandle : new ArrayList<>(decodedByWindow.keySet())) {
			ArrayDeque<DecodedFrame> queue = decodedByWindow.get(windowHandle);
			if(queue == null) continue;
			DecodedFrame frame;
			synchronized(queue) {
				frame = queue.pollLast();
				queue.clear();
			}
			if(frame != null) {
				applyDecodedFrame(frame);
			}
		}
	}
	
	/**
	 * 渲染线程：把解码好的 ARGB 帧写入纹理并上传（原地内存写入 + upload）。
	 * 纹理尺寸随帧变化时重建。
	 */
	private void applyDecodedFrame(DecodedFrame frame) {
		long windowHandle = frame.windowHandle;
		int decodedW = frame.width;
		int decodedH = frame.height;
		
		TextureEntry entry = textureCache.get(windowHandle);
		
		// 尺寸变化或首次创建
		if(entry == null || entry.width != decodedW || entry.height != decodedH) {
			destroyTexture(windowHandle);
			entry = createTexture(windowHandle, decodedW, decodedH);
			if(entry == null) return;
		}
		
		// === 原地更新：直接写入 NativeImage 原生内存 ===
		try {
			NativeImage nativeImage = entry.texture.getPixels();
			if(nativeImage == null || nativeImage.isClosed()) {
				// NativeImage 无效，重建
				destroyTexture(windowHandle);
				entry = createTexture(windowHandle, decodedW, decodedH);
				if(entry == null) return;
				nativeImage = entry.texture.getPixels();
				if(nativeImage == null) return;
			}
			
			// 通过 getPointer() 直接访问原生内存
			long ptr = nativeImage.getPointer();
			if(ptr == 0L) {
				LOGGER.warn("NativeImage pointer is null for window 0x{}", Long.toHexString(windowHandle));
				return;
			}
			
			// NativeImage 内存布局：每像素 4 字节，通道字节序 = A,B,G,R（pixel offset 0 = A 字节）
			// 必须用 memPutByte 逐字节写入 — putInt 会按 JVM LE 字节序把 int 写为 [R,G,B,A],
			// 索引 0 是 A 而不是 R，等于把通道对调，渲染出来全花。逐字节写 A,B,G,R 才行。
			writeArgbPixelsToNative(ptr, frame.argbPixels);
			
			// 上传到 GPU（不重建纹理对象，不重新 register）
			entry.texture.upload();
			entry.lastUpdate = System.currentTimeMillis();
			
		} catch(Exception e) {
			LOGGER.error("Failed to update texture in-place for window 0x{}", Long.toHexString(windowHandle), e);
			// 回退：完全重建
			destroyTexture(windowHandle);
			entry = createTexture(windowHandle, decodedW, decodedH);
			if(entry != null) {
				try {
					writePixelsAndUpload(entry, frame.argbPixels, decodedW, decodedH);
				} catch(Exception e2) {
					LOGGER.error("Fallback also failed", e2);
				}
			}
		}
	}
	
	/**
	 * 惰性获取解码线程池（clear() 关闭后重建，保证重连后仍可用）
	 */
	private ExecutorService ensureExecutor() {
		synchronized(executorLock) {
			if(decodeExecutor == null || decodeExecutor.isShutdown() || decodeExecutor.isTerminated()) {
				decodeExecutor = Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "waylandcraft-jpeg-decode");
					t.setDaemon(true); // 守护线程：Minecraft 退出时不阻塞 JVM
					return t;
				});
			}
			return decodeExecutor;
		}
	}
	
	private void shutdownExecutor() {
		synchronized(executorLock) {
			if(decodeExecutor != null) {
				decodeExecutor.shutdownNow();
				decodeExecutor = null;
			}
		}
	}
	
	/**
	 * 写入像素并上传（用于首次创建和回退）
	 */
	private void writePixelsAndUpload(TextureEntry entry, int[] argbPixels, int width, int height) {
		NativeImage nativeImage = entry.texture.getPixels();
		if(nativeImage == null) return;
		
		long ptr = nativeImage.getPointer();
		if(ptr == 0L) return;
		
		writeArgbPixelsToNative(ptr, argbPixels);
		entry.texture.upload();
	}
	
	/**
	 * 把 ARGB int 像素数组写入 NativeImage 原生内存（通过绝对 ptr）
	 * 
	 * MC 26.x NativeImage（new NativeImage(w,h,false) → Format.RGBA）的内存布局：
	 *   redOffset=0, greenOffset=8, blueOffset=16, alphaOffset=24
	 * 即 little-endian 内存 [R, G, B, A]（byte0 = R）。
	 * 
	 * 用 memPutInt 一次写 4 字节（JVM little-endian）：
	 *   int V = (a<<24)|(b<<16)|(g<<8)|r → 内存 [r, g, b, a] ✓
	 */
	private static void writeArgbPixelsToNative(long ptr, int[] argbPixels) {
		long offset = ptr;
		for(int i = 0; i < argbPixels.length; i++) {
			int argb = argbPixels[i];
			int a = (argb >>> 24) & 0xFF;
			int r = (argb >>> 16) & 0xFF;
			int g = (argb >>> 8) & 0xFF;
			int b = argb & 0xFF;
			MemoryUtil.memPutInt(offset, (a << 24) | (b << 16) | (g << 8) | r);
			offset += 4;
		}
	}
	
	@Nullable
	private TextureEntry createTexture(long windowHandle, int width, int height) {
		try {
			if(textureCache.size() >= MAX_CACHED_TEXTURES) {
				cleanupOldTextures();
			}
			
			NativeImage image = new NativeImage(width, height, false);
			DynamicTexture texture = new DynamicTexture(() -> "remote_window_" + Long.toHexString(windowHandle), image);
			
			Identifier location = getTextureLocation(windowHandle);
			TextureManager textureManager = Minecraft.getInstance().getTextureManager();
			textureManager.register(location, texture);
			
			TextureEntry entry = new TextureEntry(texture, location, width, height);
			textureCache.put(windowHandle, entry);
			
			LOGGER.debug("Created texture for window 0x{} ({}x{})", Long.toHexString(windowHandle), width, height);
			return entry;
		} catch(Exception e) {
			LOGGER.error("Failed to create texture for window 0x{}", Long.toHexString(windowHandle), e);
			return null;
		}
	}
	
	public void destroyTexture(long windowHandle) {
		// 清空该窗口的待解码槽位与解码结果队列（在途任务会通过槽位校验自行丢弃）
		pendingByWindow.remove(windowHandle);
		decodedByWindow.remove(windowHandle);
		
		TextureEntry entry = textureCache.remove(windowHandle);
		if(entry != null) {
			TextureManager textureManager = Minecraft.getInstance().getTextureManager();
			textureManager.release(entry.location);
			LOGGER.debug("Destroyed texture for window 0x{}", Long.toHexString(windowHandle));
		}
	}
	
	@Nullable
	public Identifier getTextureLocation_obj(long windowHandle) {
		TextureEntry entry = textureCache.get(windowHandle);
		return entry != null ? entry.location : null;
	}
	
	public boolean hasTexture(long windowHandle) {
		return textureCache.containsKey(windowHandle);
	}
	
	public int[] getTextureDimensions(long windowHandle) {
		TextureEntry entry = textureCache.get(windowHandle);
		if(entry == null) return null;
		return new int[]{entry.width, entry.height};
	}
	
	private void cleanupOldTextures() {
		long now = System.currentTimeMillis();
		long threshold = 30000;
		
		textureCache.entrySet().removeIf(entry -> {
			if(now - entry.getValue().lastUpdate > threshold) {
				TextureManager textureManager = Minecraft.getInstance().getTextureManager();
				textureManager.release(entry.getValue().location);
				LOGGER.debug("Cleaned up old texture for window 0x{}", Long.toHexString(entry.getKey()));
				return true;
			}
			return false;
		});
	}
	
	public void tick() {
		// 渲染线程：把解码好的帧上传到 GPU
		flushPendingUploads();
		
		tickCounter++;
		if(tickCounter >= CLEANUP_INTERVAL) {
			tickCounter = 0;
			cleanupOldTextures();
		}
	}
	
	public void clear() {
		pendingByWindow.clear();
		decodedByWindow.clear();
		
		TextureManager textureManager = Minecraft.getInstance().getTextureManager();
		textureCache.values().forEach(entry -> textureManager.release(entry.location));
		textureCache.clear();
		
		shutdownExecutor();
		LOGGER.info("RemoteWindowRenderer cleared");
	}
	
	/**
	 * 每窗口一个的待解码槽位：publish() 覆盖旧帧（丢中间帧），take() 取出当前最新帧。
	 * 由 updateTexture 所在线程写、解码工作线程读，Job 引用用 volatile 保证可见性。
	 */
	private static class PendingFrame {
		final AtomicBoolean inFlight = new AtomicBoolean(false);
		private volatile Job current;
		
		void publish(int x, int y, int width, int height, byte[] jpegData) {
			current = new Job(x, y, width, height, jpegData);
		}
		
		@Nullable
		Job take() {
			Job job = current;
			current = null;
			return job;
		}
		
		boolean hasPending() {
			return current != null;
		}
		
		static class Job {
			final int x, y, width, height;
			final byte[] jpegData;
			Job(int x, int y, int width, int height, byte[] jpegData) {
				this.x = x;
				this.y = y;
				this.width = width;
				this.height = height;
				this.jpegData = jpegData;
			}
		}
	}
	
	/** 工作线程解码结果（ARGB），交由渲染线程上传 */
	private static class DecodedFrame {
		final long windowHandle;
		final int x, y;
		final int width, height;
		final int[] argbPixels;
		DecodedFrame(long windowHandle, int x, int y, int width, int height, int[] argbPixels) {
			this.windowHandle = windowHandle;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.argbPixels = argbPixels;
		}
	}
	
	private static class TextureEntry {
		DynamicTexture texture;
		final Identifier location;
		final int width;
		final int height;
		long lastUpdate;
		
		TextureEntry(DynamicTexture texture, Identifier location, int width, int height) {
			this.texture = texture;
			this.location = location;
			this.width = width;
			this.height = height;
			this.lastUpdate = System.currentTimeMillis();
		}
	}
}
