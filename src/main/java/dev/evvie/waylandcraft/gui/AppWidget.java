package dev.evvie.waylandcraft.gui;

import java.util.function.Consumer;

import dev.evvie.waylandcraft.desktop.DesktopEntry;
import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * 应用卡片 —— 统一科幻风格（圆角卡片 + 选中霓虹描边 + hover 高亮）。
 */
public class AppWidget extends AbstractWidget {
	
	public final DesktopEntry entry;
	private Consumer<DesktopEntry> launchAction;
	private Font font;
	
	public AppWidget(DesktopEntry entry, Consumer<DesktopEntry> launchAction) {
		super(0, 0, 0, 0, Component.literal(getTitle(entry)));
		this.entry = entry;
		this.launchAction = launchAction;
		this.font = Minecraft.getInstance().font;
	}
	
	private static String getTitle(DesktopEntry entry) {
		return entry.name != null ? entry.name : entry.appId;
	}
	
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		int x = getX() + 1;
		int y = getY() + 1;
		int width = getWidth() - 2;
		int height = getHeight() - 2;
		boolean selected = isFocused();
		boolean hovered = isHoveredOrFocused();
		
		// 卡片底
		if(selected) {
			PanelRenderer.neonFilled(context, x, y, width, height);
			PanelRenderer.neonBorder(context, x - 1, y - 1, width + 2, height + 2);
		}
		else {
			PanelRenderer.field(context, x, y, width, height);
			if(hovered) {
				PanelRenderer.overlay(context, x, y, width, height, WcColors.HOVER_MASK);
				PanelRenderer.neonBorder(context, x, y, width, height);
			}
		}
		
		Identifier icon = entry.getIcon();
		int iconSize = icon != null ? height - 10 : 0;
		
		MutableComponent text = Component.literal(getTitle(entry));
		if(hovered) text = text.withStyle(ChatFormatting.UNDERLINE);
		
		context.enableScissor(x + 4, y + 4, x + width - 4, y + height - 4);
		if(icon != null) context.blit(icon, x + 5, y + 5, x + 5 + iconSize, y + 5 + iconSize, 0.0f, 1.0f, 0.0f, 1.0f);
		int color = selected ? WcColors.TEXT_ON_NEON : WcColors.TEXT;
		context.text(font, text, x + 5 + iconSize + 5, y + height / 2 - font.lineHeight / 2, color);
		context.disableScissor();
	}
	
	public void launch() {
		launchAction.accept(entry);
	}
	
	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		launch();
	}
	
	@Override
	public boolean keyPressed(KeyEvent event) {
		if(!visible || !active) return false;
		if(!event.isSelection()) return false;
		launch();
		return true;
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		this.defaultButtonNarrationText(narrationElementOutput);
	}
	
}
