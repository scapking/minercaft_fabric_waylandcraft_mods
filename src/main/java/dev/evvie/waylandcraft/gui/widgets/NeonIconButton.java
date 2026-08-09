package dev.evvie.waylandcraft.gui.widgets;

import java.time.Duration;

import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * 霓虹图标按钮 —— 替换原版 SpriteIconButton，统一科幻风格。
 * 方形小按钮，带 tooltip、hover 辉光、聚焦描边。
 */
public class NeonIconButton extends AbstractWidget {

	private final Identifier icon;
	private final int iconSize;
	private final Runnable onPress;

	public NeonIconButton(int x, int y, int size, Identifier icon, int iconSize, Component tooltip, Runnable onPress) {
		super(x, y, size, size, tooltip);
		this.icon = icon;
		this.iconSize = iconSize;
		this.onPress = onPress;
		this.setTooltip(Tooltip.create(tooltip));
		this.setTooltipDelay(Duration.ofMillis(700));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		int x = getX();
		int y = getY();
		int size = getWidth();
		boolean hovered = isHoveredOrFocused();

		if(hovered && active) {
			PanelRenderer.glow(context, x + size / 2, y + size / 2, size + 20);
		}

		PanelRenderer.field(context, x, y, size, size);

		if(isFocused() && active) {
			PanelRenderer.neonBorder(context, x, y, size, size);
		}

		if(icon != null) {
			int ix = x + size / 2 - iconSize / 2;
			int iy = y + size / 2 - iconSize / 2;
			context.blit(icon, ix, iy, ix + iconSize, iy + iconSize, 0.0f, 1.0f, 0.0f, 1.0f);
		}
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
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
			onPress.run();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if(!(active && visible)) return false;
		if(!event.isSelection()) return false;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		onPress.run();
		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		this.defaultButtonNarrationText(narrationElementOutput);
	}
}
