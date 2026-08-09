package dev.evvie.waylandcraft.shared;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * 远程窗口渲染器（终极优化版）
 * 
 * 优化内容：
 * 1. 原地纹理更新：保留 DynamicTexture + NativeImage，不 close/new/register
 * 2. 直接内存写入：通过 getPointer() + MemoryUtil 写入原生内存，跳过 setPixelABGR JNI
 * 3. 批量解码：getRGB 一次获取所有像素
 */
public class RemoteWindowRenderer {
	
	private static final Logger LOGGER = LoggerFactory.getLogger("waylandcraft-remote-renderer");
	
	private final Map<Long, TextureEntry> textureCache = new ConcurrentHashMap<>();
	
	private static final int MAX_CACHED_TEXTURES = 50;
	private static final int CLEANUP_INTERVAL = 600;
	private int tickCounter = 0;
	
	public RemoteWindowRenderer() {
		LOGGER.info("RemoteWindowRenderer initialized");
	}
	
	private Identifier getTextureLocation(long windowHandle) {
		return Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "remote_" + windowHandle);
	}
	
	/**
	 * 更新远程窗口纹理（终极优化：原地内存写入 + upload）
	 */
	public void updateTexture(long windowHandle, int x, int y, int width, int height, byte[] jpegData) {
		TextureEntry entry = textureCache.get(windowHandle);
		
		// 解码 JPEG
		int[] argbPixels;
		int decodedW, decodedH;
		try {
			BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(jpegData));
			if(bufferedImage == null) {
				LOGGER.warn("ImageIO.read returned null ({} bytes)", jpegData.length);
				return;
			}
			decodedW = bufferedImage.getWidth();
			decodedH = bufferedImage.getHeight();
			// 批量获取所有像素（1次JNI调用）
			argbPixels = bufferedImage.getRGB(0, 0, decodedW, decodedH, null, 0, decodedW);
		} catch(Exception e) {
			LOGGER.error("Failed to decode JPEG ({} bytes)", jpegData.length, e);
			return;
		}
		
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
			writeArgbPixelsToNative(ptr, argbPixels);
			
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
					writePixelsAndUpload(entry, argbPixels, decodedW, decodedH);
				} catch(Exception e2) {
					LOGGER.error("Fallback also failed", e2);
				}
			}
		}
	}
	
	/**
	 * 直接上传 RGBA 像素（用于 Portal ScreenCast 原始帧，不经过 JPEG 解码）
	 * 字节顺序为 R,G,B,A
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
			
			// NativeImage 内存布局：每像素 4 字节，通道字节序 = A,B,G,R（offset 0 = A 字节）
			// Portal 帧是 R,G,B,A → 需要重排为 A,B,G,R
			long offset = ptr;
			for(int i = 0; i < width * height; i++) {
				int base = i * 4;
				byte r = rgbaData[base];
				byte g = rgbaData[base + 1];
				byte b = rgbaData[base + 2];
				byte a = rgbaData[base + 3];
				MemoryUtil.memPutByte(offset,     a);
				MemoryUtil.memPutByte(offset + 1, b);
				MemoryUtil.memPutByte(offset + 2, g);
				MemoryUtil.memPutByte(offset + 3, r);
				offset += 4;
			}
			
			entry.texture.upload();
			entry.lastUpdate = System.currentTimeMillis();
		} catch(Exception e) {
			LOGGER.error("Failed to update RGBA texture for window 0x{}", Long.toHexString(windowHandle), e);
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
	 * 字节序：A,B,G,R（offset 0 = A 字节）
	 * 一次写一个 byte，避免堆 ByteBuffer + putInt 的 JVM 字节序重排坑。
	 */
	private static void writeArgbPixelsToNative(long ptr, int[] argbPixels) {
		long offset = ptr;
		for(int i = 0; i < argbPixels.length; i++) {
			int argb = argbPixels[i];
			int a = (argb >>> 24) & 0xFF;
			int r = (argb >>> 16) & 0xFF;
			int g = (argb >>> 8) & 0xFF;
			int b = argb & 0xFF;
			MemoryUtil.memPutByte(offset,     (byte) a);
			MemoryUtil.memPutByte(offset + 1, (byte) b);
			MemoryUtil.memPutByte(offset + 2, (byte) g);
			MemoryUtil.memPutByte(offset + 3, (byte) r);
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
		tickCounter++;
		if(tickCounter >= CLEANUP_INTERVAL) {
			tickCounter = 0;
			cleanupOldTextures();
		}
	}
	
	public void clear() {
		TextureManager textureManager = Minecraft.getInstance().getTextureManager();
		textureCache.values().forEach(entry -> textureManager.release(entry.location));
		textureCache.clear();
		LOGGER.info("RemoteWindowRenderer cleared");
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
