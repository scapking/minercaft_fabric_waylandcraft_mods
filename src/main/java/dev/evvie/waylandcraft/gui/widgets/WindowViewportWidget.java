package dev.evvie.waylandcraft.gui.widgets;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import dev.evvie.waylandcraft.gui.theme.WcTheme;
import dev.evvie.waylandcraft.gui.theme.WindowLayout;
import dev.evvie.waylandcraft.gui.theme.WindowLayout.ContentRect;
import dev.evvie.waylandcraft.render.RenderUtils;
import dev.evvie.waylandcraft.render.WindowFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * 窗口画面复用组件（Window Viewport Widget）。
 *
 * 把「窗口画面」封装成一个可嵌入任意 Screen / HUD 的组件：
 *  - 本地 Wayland 窗口：传 {@link WindowFramebuffer}
 *  - 远程共享窗口：传纹理 {@link Identifier}（RemoteWindowRenderer 产出）
 *  - 统一渲染：标题栏 + 圆角边框 + contain 适配画面 + 聚焦辉光
 *
 * 使用示例（本地窗口）：
 *   viewport.setLocalWindow(fb, fb.getWidth(), fb.getHeight(), title, icon);
 * 使用示例（远程窗口）：
 *   viewport.setRemoteWindow(textureId, w, h, title);
 *
 * 所有消费方（WindowManagerScreen / HUD pinned / 共享窗口预览）
 * 共用本组件，不再各自手写矩阵变换。
 */
public class WindowViewportWidget extends AbstractWidget {

	public static final int TITLE_BAR_HEIGHT = WcTheme.WINDOW_TITLE_BAR_HEIGHT;

	// ===== 数据源（二选一） =====
	@Nullable
	private WindowFramebuffer framebuffer;
	@Nullable
	private Identifier remoteTexture;
	/** 源内容尺寸（物理像素） */
	private int sourceWidth = 0;
	private int sourceHeight = 0;

	// ===== 展示信息 =====
	private Component title = Component.literal("");
	@Nullable
	private Identifier icon;
	/** 窗口聚焦态（决定霓虹描边与辉光） */
	private boolean windowFocused = false;
	/** 远程纹理是否需要 V 翻转（top-down 源） */
	private boolean flipV = false;

	// ===== 交互钩子 =====
	@Nullable
	private Runnable onActivate;

	public WindowViewportWidget(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty());
	}

	// ==================== 数据源 ====================

	public void setLocalWindow(WindowFramebuffer fb, Component title, @Nullable Identifier icon) {
		this.framebuffer = fb;
		this.remoteTexture = null;
		this.sourceWidth = fb != null ? fb.getWidth() : 0;
		this.sourceHeight = fb != null ? fb.getHeight() : 0;
		this.title = title;
		this.icon = icon;
		this.flipV = false;
	}

	public void setRemoteWindow(Identifier texture, int width, int height, Component title, boolean flipV) {
		this.remoteTexture = texture;
		this.framebuffer = null;
		this.sourceWidth = width;
		this.sourceHeight = height;
		this.title = title;
		this.icon = null;
		this.flipV = flipV;
	}

	public void clear() {
		this.framebuffer = null;
		this.remoteTexture = null;
		this.sourceWidth = 0;
		this.sourceHeight = 0;
		this.title = Component.literal("");
		this.icon = null;
		this.windowFocused = false;
	}

	// ==================== 状态 ====================

	public void setWindowFocused(boolean focused) {
		this.windowFocused = focused;
	}

	public boolean hasContent() {
		return framebuffer != null || remoteTexture != null;
	}

	public void setOnActivate(@Nullable Runnable onActivate) {
		this.onActivate = onActivate;
	}

	/**
	 * 静态渲染入口 —— 不依赖 widget 实例，适合 HUD 等非 Screen 场景。
	 * 等价于创建一个组件并立即渲染一次。
	 */
	public static void render(GuiGraphicsExtractor context, WindowFramebuffer fb, int x, int y, int w, int h,
			Component title, @Nullable Identifier icon, boolean focused) {
		WindowViewportWidget vp = new WindowViewportWidget(x, y, w, h);
		vp.setLocalWindow(fb, title, icon);
		vp.setWindowFocused(focused);
		vp.extractWidgetRenderState(context, 0, 0, 0.0f);
	}

	/**
	 * 静态渲染入口（远程窗口纹理版）。
	 */
	public static void renderRemote(GuiGraphicsExtractor context, @Nullable Identifier texture, int srcW, int srcH,
			int x, int y, int w, int h, Component title, boolean focused) {
		WindowViewportWidget vp = new WindowViewportWidget(x, y, w, h);
		vp.setRemoteWindow(texture, srcW, srcH, title, false);
		vp.setWindowFocused(focused);
		vp.extractWidgetRenderState(context, 0, 0, 0.0f);
	}

	// ==================== 渲染 ====================

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();

		boolean hovered = isHoveredOrFocused();

		// 聚焦辉光
		if(windowFocused) {
			PanelRenderer.glow(context, x + w / 2, y + h / 2, Math.max(w, h) + 32);
		}

		// 组件底色（内容区）
		ContentRect content = WindowLayout.contentRect(x, y, w, h, TITLE_BAR_HEIGHT);
		PanelRenderer.field(context, content.x(), content.y(), content.width(), content.height());

		// 标题栏
		renderTitleBar(context, x, y, w);

		// 内容画面
		renderContent(context, content);

		// 边框：聚焦=霓虹，否则暗
		if(windowFocused) {
			PanelRenderer.neonBorder(context, x, y, w, h);
		}
		else if(hovered && hasContent()) {
			PanelRenderer.overlay(context, x, y, w, h, WcColors.HOVER_MASK);
		}
	}

	private void renderTitleBar(GuiGraphicsExtractor context, int x, int y, int w) {
		PanelRenderer.titleBar(context, x, y, w, TITLE_BAR_HEIGHT);

		Font font = Minecraft.getInstance().font;
		int textX = x + WcTheme.WINDOW_TITLE_PAD;
		int iconSize = TITLE_BAR_HEIGHT - 4;

		if(icon != null) {
			context.blit(icon, textX, y + 2, textX + iconSize, y + 2 + iconSize, 0.0f, 1.0f, 0.0f, 1.0f);
			textX += iconSize + WcTheme.SPACING_SMALL;
		}

		// 标题截断
		int maxTextWidth = w - (textX - x) - WcTheme.WINDOW_TITLE_PAD;
		Component display = title;
		if(font.width(display) > maxTextWidth && maxTextWidth > 0) {
			display = Component.literal(font.plainSubstrByWidth(display.getString(), maxTextWidth));
		}
		context.text(font, display, textX, y + (TITLE_BAR_HEIGHT - font.lineHeight) / 2,
				windowFocused ? WcColors.TEXT : WcColors.TEXT_DIM);

		// 聚焦指示点
		if(windowFocused) {
			context.fill(x + w - 3, y + TITLE_BAR_HEIGHT / 2 - 1, x + w - 1, y + TITLE_BAR_HEIGHT / 2 + 1, WcColors.CYAN);
		}
	}

	private void renderContent(GuiGraphicsExtractor context, ContentRect content) {
		if(sourceWidth <= 0 || sourceHeight <= 0 || !hasContent()) {
			Font font = Minecraft.getInstance().font;
			Component msg = Component.literal("NO SIGNAL");
			context.text(font, msg, content.x() + content.width() / 2 - font.width(msg) / 2,
					content.y() + content.height() / 2 - font.lineHeight / 2, WcColors.TEXT_DISABLED);
			return;
		}

		ContentRect rect = WindowLayout.fit(sourceWidth, sourceHeight,
				content.width(), content.height(), WindowLayout.FitMode.CONTAIN);

		if(rect.width() <= 0 || rect.height() <= 0) return;

		if(framebuffer != null && framebuffer.isValid()) {
			// 本地窗口帧缓冲 → 统一 2D 渲染
			RenderUtils.renderFramebuffer2D(context, framebuffer,
					content.x() + rect.x(), content.y() + rect.y(), rect.width(), rect.height());
		}
		else if(remoteTexture != null) {
			// 远程共享窗口纹理 → 统一 2D 渲染（与本地 framebuffer 同一 WINDOW_BLIT 管线）
			RenderUtils.renderTexture2D(context, remoteTexture,
					content.x() + rect.x(), content.y() + rect.y(), rect.width(), rect.height(), flipV);
		}
	}

	// ==================== 交互 ====================

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(!(visible && active)) return false;
		if(event.button() != 0) return false;
		if(!isMouseOver(event.x(), event.y())) return false;
		if(onActivate != null) onActivate.run();
		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
	}
}
