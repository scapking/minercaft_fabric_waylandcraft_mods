package dev.evvie.waylandcraft.gui.theme;

/**
 * WaylandCraft 科幻 UI 色板。
 *
 * 所有颜色统一使用 MC 渲染管线的 ARGB 格式（0xAARRGGBB）。
 * 禁止在 UI 代码中混用 java.awt.Color / 裸十六进制字面量 —— 一律引用本类常量。
 *
 * 设计语言：深空底色 + 霓虹青主色 + 紫罗兰强调 + 高亮描边。
 */
public final class WcColors {

	private WcColors() {}

	// ==================== 基础 ====================
	/** 全透明 */
	public static final int TRANSPARENT = 0x00000000;

	// ==================== 底色（深空） ====================
	/** 页面主背景 —— 近黑深蓝 */
	public static final int BG_BASE = 0xF00B0F1A;
	/** 面板背景 —— 半透明深蓝 */
	public static final int PANEL = 0xE6121826;
	/** 面板背景（更浅，用于悬浮/强调层） */
	public static final int PANEL_LIGHT = 0xF21A2334;
	/** 面板内嵌区（列表、输入区） */
	public static final int PANEL_INSET = 0xE60D1320;

	// ==================== 边框 ====================
	/** 普通边框 */
	public static final int BORDER = 0x6622384F;
	/** 强边框（hover） */
	public static final int BORDER_STRONG = 0xB82C4A63;
	/** 高亮边框（选中/焦点）—— 霓虹青 */
	public static final int BORDER_FOCUS = 0xFF00E5FF;

	// ==================== 霓虹主色 ====================
	/** 霓虹青 —— 主强调色 */
	public static final int CYAN = 0xFF00E5FF;
	/** 霓虹青（暗） */
	public static final int CYAN_DIM = 0x6600E5FF;
	/** 霓虹青（极暗，用作辉光底） */
	public static final int CYAN_GLOW = 0x2200E5FF;
	/** 霓虹青（更亮，hover 用） */
	public static final int CYAN_BRIGHT = 0xFF33EDFF;

	// ==================== 强调色 ====================
	/** 紫罗兰 —— 次强调色 */
	public static final int VIOLET = 0xFFA78BFA;
	/** 紫罗兰（暗） */
	public static final int VIOLET_DIM = 0x66A78BFA;
	/** 品红 —— 特殊/危险动作 */
	public static final int MAGENTA = 0xFFF472B6;

	// ==================== 状态色 ====================
	/** 成功 */
	public static final int SUCCESS = 0xFF34D399;
	/** 警告 */
	public static final int WARNING = 0xFFFBBF24;
	/** 危险 */
	public static final int DANGER = 0xFFF87171;
	/** 危险（暗） */
	public static final int DANGER_DIM = 0x66F87171;

	// ==================== 文本 ====================
	/** 主文本 */
	public static final int TEXT = 0xFFE2E8F0;
	/** 次文本 */
	public static final int TEXT_DIM = 0xFF94A3B8;
	/** 禁用文本 */
	public static final int TEXT_DISABLED = 0xFF475569;
	/** 反色文本（深色字，用于发光按钮） */
	public static final int TEXT_ON_NEON = 0xFF04121A;

	// ==================== 遮罩 ====================
	/** 悬停遮罩 */
	public static final int HOVER_MASK = 0x2200E5FF;
	/** 按下遮罩 */
	public static final int PRESSED_MASK = 0x4000E5FF;
	/** 选中遮罩 */
	public static final int SELECTED_MASK = 0x3300E5FF;
	/** 暗化遮罩（禁用/降权） */
	public static final int DIM_MASK = 0x80000000;

	// ==================== 窗口（窗口画面组件专属） ====================
	/** 窗口标题栏 */
	public static final int WIN_TITLEBAR = 0xE6131B2E;
	/** 窗口标题栏（聚焦） */
	public static final int WIN_TITLEBAR_FOCUSED = 0xF2142240;
	/** 窗口内容区底衬（画面未就绪时） */
	public static final int WIN_PLACEHOLDER = 0xE60A0E18;
	/** 窗口边框 */
	public static final int WIN_BORDER = 0x994A6FA5;
	/** 窗口边框（聚焦） */
	public static final int WIN_BORDER_FOCUSED = 0xFF00E5FF;
	/** 窗口聚焦辉光 */
	public static final int WIN_GLOW = 0x3300E5FF;

	// ==================== 工具 ====================
	/** 拼接 ARGB（不依赖 MC 类，便于纯逻辑测试） */
	public static int argb(int a, int r, int g, int b) {
		return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
	}

	/** 取 alpha 通道 */
	public static int alpha(int argb) {
		return (argb >>> 24) & 0xFF;
	}

	/** 取 red 通道 */
	public static int red(int argb) {
		return (argb >>> 16) & 0xFF;
	}

	/** 取 green 通道 */
	public static int green(int argb) {
		return (argb >>> 8) & 0xFF;
	}

	/** 取 blue 通道 */
	public static int blue(int argb) {
		return argb & 0xFF;
	}

	/**
	 * 线性插值两个 ARGB 颜色（含 alpha）。
	 * 纯逻辑，可单测。
	 */
	public static int lerp(int from, int to, float t) {
		if(t <= 0.0f) return from;
		if(t >= 1.0f) return to;
		int a = Math.round(alpha(from) + (alpha(to) - alpha(from)) * t);
		int r = Math.round(red(from) + (red(to) - red(from)) * t);
		int g = Math.round(green(from) + (green(to) - green(from)) * t);
		int b = Math.round(blue(from) + (blue(to) - blue(from)) * t);
		return argb(a, r, g, b);
	}

	/**
	 * 叠加一个半透明前景色到不透明背景上（over 运算）。
	 * 纯逻辑，可单测。
	 */
	public static int over(int background, int foreground) {
		int fa = alpha(foreground);
		if(fa == 0) return background;
		if(fa == 255) return foreground;
		float f = fa / 255.0f;
		int ba = alpha(background);
		int outA = Math.round(fa + ba * (1.0f - f));
		int outR = Math.round(red(foreground) * f + red(background) * ba / 255.0f * (1.0f - f));
		int outG = Math.round(green(foreground) * f + green(background) * ba / 255.0f * (1.0f - f));
		int outB = Math.round(blue(foreground) * f + blue(background) * ba / 255.0f * (1.0f - f));
		return argb(outA, outR, outG, outB);
	}
}
