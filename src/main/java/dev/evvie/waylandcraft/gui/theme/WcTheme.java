package dev.evvie.waylandcraft.gui.theme;

/**
 * WaylandCraft 科幻 UI 设计规范（Design Tokens）。
 *
 * 所有尺寸/间距/圆角统一引用本类，禁止在 Screen 里硬编码魔法数字。
 * 布局节奏：基础单位 4px，控件高度 20px，圆角 6px。
 */
public final class WcTheme {

	private WcTheme() {}

	// ==================== 布局节奏 ====================
	/** 基础网格单位 */
	public static final int GRID = 4;
	/** 元素间小间距 */
	public static final int SPACING_SMALL = 4;
	/** 元素间标准间距 */
	public static final int SPACING = 8;
	/** 区块间大间距 */
	public static final int SPACING_LARGE = 16;
	/** 页面外边距 */
	public static final int MARGIN = 12;
	/** 页面大外边距 */
	public static final int MARGIN_LARGE = 24;

	// ==================== 控件尺寸 ====================
	/** 标准控件高度 */
	public static final int CONTROL_HEIGHT = 20;
	/** 标准按钮高度（大） */
	public static final int CONTROL_HEIGHT_LARGE = 24;
	/** 图标按钮尺寸 */
	public static final int ICON_BUTTON_SIZE = 24;
	/** 最小可点击宽度 */
	public static final int MIN_CLICK_WIDTH = 32;
	/** 标题栏高度 */
	public static final int TITLE_BAR_HEIGHT = 24;

	// ==================== 圆角 ====================
	/** 小圆角（按钮、输入框） */
	public static final int RADIUS_SMALL = 4;
	/** 标准圆角（面板） */
	public static final int RADIUS = 6;
	/** 大圆角（卡片） */
	public static final int RADIUS_LARGE = 10;

	// ==================== 边框 ====================
	/** 标准边框宽度 */
	public static final int BORDER_WIDTH = 1;
	/** 高亮边框宽度 */
	public static final int BORDER_WIDTH_FOCUS = 2;

	// ==================== 窗口组件 ====================
	/** 窗口标题栏高度 */
	public static final int WINDOW_TITLE_BAR_HEIGHT = 18;
	/** 窗口标题栏内边距 */
	public static final int WINDOW_TITLE_PAD = 6;
	/** 窗口画面与边框间距 */
	public static final int WINDOW_CONTENT_PAD = 0;
	/** 窗口组件最小宽度 */
	public static final int WINDOW_MIN_WIDTH = 120;
	/** 窗口组件最小高度 */
	public static final int WINDOW_MIN_HEIGHT = 80;

	// ==================== 文字 ====================
	/** 小标题字号由 font.lineHeight 决定，这里定义行高基准 */
	public static final int FONT_LINE = 9;
	/** 标题下边距 */
	public static final int TITLE_BOTTOM_MARGIN = 8;

	// ==================== 列表 ====================
	/** 列表行高 */
	public static final int LIST_ROW_HEIGHT = 20;
	/** 列表行内边距 */
	public static final int LIST_ROW_PAD = 6;
}
