package dev.evvie.waylandcraft.render;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iris（光影）兼容检测。
 * 
 * Iris 通过 mixin 全局拦截 {@code GlDevice.getOrCompilePipeline}（{@code iris$redirectIrisProgram}），
 * 把所有自定义 RenderPipeline / RenderType 交给它的 shader 系统重写；WaylandCraft 的自定义
 * shader 管线（rendertype_window / window_blit 等）不被 Iris 认识 → 渲染时抛异常 → 窗口画面出不来。
 * 
 * 检测到 Iris 加载时，世界空间窗口渲染改用原版 entity 管线（Iris 认识并能正确处理），
 * 2D GUI 渲染改用原版 GUI_TEXTURED 管线，从而在光影开启时也能正常显示窗口。
 */
public final class IrisCompat {
	
	private static final Logger LOGGER = LoggerFactory.getLogger("waylandcraft-iris-compat");
	
	private static final boolean IRIS_LOADED = detectIris();
	
	private IrisCompat() {}
	
	private static boolean detectIris() {
		try {
			boolean loaded = FabricLoader.getInstance().isModLoaded("iris");
			if(loaded) {
				LOGGER.info("Iris (shaders) detected - using vanilla pipeline fallback for window rendering");
			}
			return loaded;
		} catch(Throwable t) {
			// FabricLoader 不可用时（异常环境）保守起见当作未加载
			return false;
		}
	}
	
	/**
	 * 当前是否加载了 Iris 光影 mod
	 */
	public static boolean isIrisLoaded() {
		return IRIS_LOADED;
	}
	
}
