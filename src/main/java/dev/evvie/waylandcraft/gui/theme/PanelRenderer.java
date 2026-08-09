package dev.evvie.waylandcraft.gui.theme;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 科幻 UI 绘制原语 —— 所有面板/边框/辉光都走这里。
 *
 * 使用 9-patch sprite（textures/gui/sprites/*_9.png，border=6），
 * 圆角在任意尺寸下不变形；聚焦辉光用独立发光贴图。
 */
public final class PanelRenderer {

	private PanelRenderer() {}

	public static final Identifier PANEL = sprite("panel_9");
	public static final Identifier FIELD = sprite("field_9");
	public static final Identifier NEON_BORDER = sprite("neon_border_9");
	public static final Identifier NEON_FILLED = sprite("neon_filled_9");
	public static final Identifier NEON_FILLED_DIM = sprite("neon_filled_dim_9");
	public static final Identifier TITLE_BAR = sprite("titlebar_9");
	public static final Identifier GLOW = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "glow");

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, name);
	}

	/** 玻璃面板（半透明深蓝 + 圆角） */
	public static void panel(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		context.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, h);
	}

	/** 内嵌区（列表、输入框背景） */
	public static void field(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		context.blitSprite(RenderPipelines.GUI_TEXTURED, FIELD, x, y, w, h);
	}

	/** 霓虹描边（聚焦/选中态） */
	public static void neonBorder(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		context.blitSprite(RenderPipelines.GUI_TEXTURED, NEON_BORDER, x, y, w, h);
	}

	/** 霓虹填充按钮底（文字用深色） */
	public static void neonFilled(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		context.blitSprite(RenderPipelines.GUI_TEXTURED, NEON_FILLED, x, y, w, h);
	}

	/** 霓虹填充按钮底（禁用/暗态） */
	public static void neonFilledDim(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		context.blitSprite(RenderPipelines.GUI_TEXTURED, NEON_FILLED_DIM, x, y, w, h);
	}

	/** 标题栏底 */
	public static void titleBar(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		context.blitSprite(RenderPipelines.GUI_TEXTURED, TITLE_BAR, x, y, w, h);
	}

	/**
	 * 霓虹辉光（按钮/窗口聚焦时的底光）。
	 * 辉光是居中发散的，位置传中心点。
	 */
	public static void glow(GuiGraphicsExtractor context, int centerX, int centerY, int size) {
		int half = size / 2;
		context.blitSprite(RenderPipelines.GUI_TEXTURED, GLOW, centerX - half, centerY - half, size, size);
	}

	/** 混合颜色叠加（hover/按下遮罩），blend 由调用方保证 */
	public static void overlay(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + h, color);
	}
}
