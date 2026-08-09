package dev.evvie.waylandcraft.gui.widgets;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * 霓虹开关 —— 统一替代 SettingsWidget.BooleanControlElement。
 *
 * 开启：青底 + 右侧滑块 + 霓虹描边；关闭：暗底 + 左侧滑块。
 */
public class NeonToggle extends AbstractWidget {

	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;

	public NeonToggle(int x, int y, int width, int height, BooleanSupplier getter, Consumer<Boolean> setter) {
		super(x, y, width, height, Component.empty());
		this.getter = getter;
		this.setter = setter;
	}

	public boolean value() {
		return getter.getAsBoolean();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean on = value();
		boolean hovered = isHoveredOrFocused();

		if(hovered && active) {
			PanelRenderer.glow(context, x + w / 2, y + h / 2, Math.max(w, h) + 20);
		}

		if(on) {
			PanelRenderer.neonFilled(context, x, y, w, h);
		}
		else {
			PanelRenderer.field(context, x, y, w, h);
		}

		// 滑块
		int track = h - 6;
		int knob = track;
		int pad = 3;
		int knobX = on ? x + w - knob - pad : x + pad;
		int knobY = y + pad;
		context.fill(knobX, knobY, knobX + knob, knobY + knob, on ? WcColors.TEXT_ON_NEON : WcColors.TEXT_DIM);

		// 状态文字
		Font font = Minecraft.getInstance().font;
		Component label = on ? Component.literal("ON") : Component.literal("OFF");
		int lx = on ? x + pad + 1 : x + w - font.width(label) - pad - 1;
		context.text(font, label, lx, y + h / 2 - font.lineHeight / 2, on ? WcColors.TEXT_ON_NEON : WcColors.TEXT_DIM);

		if(isFocused() && active) {
			PanelRenderer.neonBorder(context, x, y, w, h);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(!(active && visible)) return false;
		if(event.button() != 0) return false;
		if(!isMouseOver(event.x(), event.y())) return false;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if(event.button() != 0) return false;
		if(isMouseOver(event.x(), event.y())) {
			toggle();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if(!(active && visible)) return false;
		if(!event.isSelection()) return false;
		toggle();
		return true;
	}

	private void toggle() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		setter.accept(!value());
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		this.defaultButtonNarrationText(narrationElementOutput);
	}
}
