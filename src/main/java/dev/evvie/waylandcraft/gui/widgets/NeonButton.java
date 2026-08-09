package dev.evvie.waylandcraft.gui.widgets;

import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import dev.evvie.waylandcraft.gui.theme.WcTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/**
 * 霓虹科幻按钮 —— 全 UI 统一按钮控件。
 *
 * 状态：禁用（暗）、默认（青）、悬停（辉光+亮）、聚焦（霓虹描边）。
 * 文字一律用深色反白（TEXT_ON_NEON）。
 */
public class NeonButton extends AbstractWidget {

	private final Runnable onPress;

	public NeonButton(int x, int y, int width, int height, Component message, Runnable onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();

		Font font = Minecraft.getInstance().font;

		boolean hovered = isHoveredOrFocused();
		boolean pressed = hovered && mouseDown;
		boolean focus = isFocused();

		// 辉光底光（悬停/聚焦）
		if(hovered && active) {
			PanelRenderer.glow(context, x + w / 2, y + h / 2, Math.max(w, h) + 24);
		}

		if(active) {
			PanelRenderer.neonFilled(context, x, y, w, h);
		}
		else {
			PanelRenderer.neonFilledDim(context, x, y, w, h);
		}

		// 聚焦描边
		if(focus && active) {
			PanelRenderer.neonBorder(context, x, y, w, h);
		}

		// 按下压暗
		if(pressed && active) {
			PanelRenderer.overlay(context, x, y, w, h, WcColors.PRESSED_MASK);
		}

		int color = active ? WcColors.TEXT_ON_NEON : WcColors.TEXT_DISABLED;
		Component msg = getMessage();
		context.text(font, msg, x + w / 2 - font.width(msg) / 2, y + h / 2 - font.lineHeight / 2, color);
	}

	private boolean mouseDown = false;

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(!(active && visible)) return false;
		if(event.button() != 0) return false;
		if(!isMouseOver(event.x(), event.y())) return false;
		mouseDown = true;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if(!mouseDown) return false;
		mouseDown = false;
		if(event.button() != 0) return false;
		if(isMouseOver(event.x(), event.y())) {
			playClickSound();
			onPress.run();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if(!(active && visible)) return false;
		if(!event.isSelection()) return false;
		playClickSound();
		onPress.run();
		return true;
	}

	private void playClickSound() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		this.defaultButtonNarrationText(narrationElementOutput);
	}
}
