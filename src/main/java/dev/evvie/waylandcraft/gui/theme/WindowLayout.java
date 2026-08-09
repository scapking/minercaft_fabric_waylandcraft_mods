package dev.evvie.waylandcraft.gui.theme;

/**
 * 窗口画面适配布局 —— 纯逻辑，不依赖 MC，可单测。
 *
 * 负责把任意尺寸的窗口帧缓冲（本地 framebuffer / 远程纹理）适配进一个
 * 视口矩形内，提供两种模式：
 *  - CONTAIN：完整显示（可能有留边，letterbox）
 *  - COVER：铺满视口（可能裁剪）
 * 以及窗口组件整体（标题栏 + 内容区）的布局计算。
 *
 * 这也是“窗口画面组件复用”的数学基础：WindowManagerScreen / HUD /
 * 共享窗口预览都通过同一套布局逻辑渲染窗口画面。
 */
public final class WindowLayout {

	private WindowLayout() {}

	public enum FitMode {
		/** 完整显示，保持宽高比 */
		CONTAIN,
		/** 铺满视口，保持宽高比 */
		COVER
	}

	/** 内容区布局结果 */
	public record ContentRect(int x, int y, int width, int height) {}

	/**
	 * 计算将 source 尺寸适配进 viewport 尺寸后的绘制矩形（居中）。
	 *
	 * @param sourceW 源内容宽度（px，物理像素）
	 * @param sourceH 源内容高度（px，物理像素）
	 * @param viewW   视口宽度（px，GUI 坐标）
	 * @param viewH   视口高度（px，GUI 坐标）
	 * @param mode    适配模式
	 * @return 绘制矩形（GUI 坐标，已居中）
	 */
	public static ContentRect fit(int sourceW, int sourceH, int viewW, int viewH, FitMode mode) {
		if(sourceW <= 0 || sourceH <= 0 || viewW <= 0 || viewH <= 0) {
			return new ContentRect(0, 0, 0, 0);
		}

		float scale;
		if(mode == FitMode.CONTAIN) {
			scale = Math.min((float) viewW / sourceW, (float) viewH / sourceH);
		}
		else {
			scale = Math.max((float) viewW / sourceW, (float) viewH / sourceH);
		}

		int drawW = Math.max(1, Math.round(sourceW * scale));
		int drawH = Math.max(1, Math.round(sourceH * scale));

		int x = viewW / 2 - drawW / 2;
		int y = viewH / 2 - drawH / 2;
		return new ContentRect(x, y, drawW, drawH);
	}

	/**
	 * 完整窗口组件布局（标题栏 + 内容区）。
	 * 组件总尺寸 = (componentW, componentH)；内容区占标题栏以下全部区域。
	 *
	 * @param componentW 组件宽度
	 * @param componentH 组件高度
	 * @param titleBarH  标题栏高度（0 表示无标题栏）
	 * @return 内容区矩形
	 */
	public static ContentRect contentRect(int componentX, int componentY, int componentW, int componentH, int titleBarH) {
		int y = componentY + titleBarH;
		return new ContentRect(componentX, y, componentW, Math.max(0, componentH - titleBarH));
	}

	/**
	 * 计算组件整体尺寸，使得窗口内容以指定比例显示在指定区域内。
	 * 用于“自适应窗口组件”：给定窗口宽高比与可用空间，反推组件宽高。
	 *
	 * @param sourceW   窗口内容宽
	 * @param sourceH   窗口内容高
	 * @param maxW      可用最大宽
	 * @param maxH      可用最大高
	 * @param titleBarH 标题栏高度
	 * @return 组件尺寸 {w, h}
	 */
	public static int[] componentSize(int sourceW, int sourceH, int maxW, int maxH, int titleBarH) {
		if(sourceW <= 0 || sourceH <= 0) return new int[] { 0, 0 };
		if(maxW <= 0 || maxH <= 0) return new int[] { 0, 0 };

		float scale = Math.min((float) maxW / sourceW, (float) (maxH - titleBarH) / sourceH);
		scale = Math.min(scale, 1.0f); // 不放大窗口画面

		int w = Math.max(1, Math.round(sourceW * scale));
		int h = Math.max(1, Math.round(sourceH * scale)) + titleBarH;
		return new int[] { w, h };
	}
}
