package dev.evvie.waylandcraft.gui;

import java.util.ArrayList;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import dev.evvie.waylandcraft.gui.theme.WcTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public abstract class SelectorWidget<T> extends AbstractWidget {
	
	private ArrayList<SelectorButton<T>> buttons = new ArrayList<SelectorButton<T>>();
	
	// Currently selected element, should always be either null or an element assigned to a button
	private T selected = null;
	
	@SuppressWarnings("unchecked")
	public SelectorWidget(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty());
		
		setEntries((T[]) new Object[0]);
	}
	
	private int unrestrictedButtonWidth() {
		return getWidth() / 5;
	}
	
	public void setEntries(T[] entries) {
		buttons.clear();
		
		int x = getX();
		int y = getY();
		int height = getHeight();
		
		for(int i = 0; i < entries.length; i++) {
			SelectorButton<T> button = new SelectorButton<T>(this, x, y, unrestrictedButtonWidth(), height);
			button.element = entries[i];
			buttons.add(button);
		}
		
		if(entries.length == 0) {
			SelectorButton<T> button = new SelectorButton<T>(this, x, y, unrestrictedButtonWidth(), height);
			button.element = null;
			buttons.add(button);
		}
		
		selectionCheck();
		arrangeButtons();
	}
	
	private void arrangeButtons() {
		int x = getX();
		int y = getY();
		int width = getWidth();
		int height = getHeight();
		
		int buttonWidth = unrestrictedButtonWidth();
		if(buttons.size() * buttonWidth > width) {
			buttonWidth = width / buttons.size();
		}
		
		for(int i = 0; i < buttons.size(); i++) {
			SelectorButton<T> button = buttons.get(i);
			button.setX(x);
			button.setY(y);
			button.setWidth(buttonWidth);
			button.setHeight(height);
			x += buttonWidth;
		}
	}
	
	public abstract Component titleForElement(T element);
	public abstract @Nullable Identifier iconForElement(T element);
	public abstract boolean elementDimColor(T element);
	
	public T selection() {
		return selected;
	}
	
	// Maintains selected element property
	private void selectionCheck() {
		if(!buttons.stream().anyMatch((b) -> b.element == selected)) {
			selected = null;
		}
	}
	
	public void select(T element) {
		this.selected = element;
		selectionCheck();
	}
	
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		for(int i = 0; i < buttons.size(); i++) {
			SelectorButton<T> b = buttons.get(i);
			
			b.visible = this.visible;
			b.selected = b.element == selected;
			
			if(b.element != null) {
				b.setMessage(titleForElement(b.element));
				b.dimColor = elementDimColor(b.element);
				b.icon = iconForElement(b.element);
			}
			else {
				b.setMessage(Component.empty());
				b.dimColor = false;
				b.icon = null;
			}
			
			b.extractRenderState(context, mouseX, mouseY, partialTicks);
		}
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(!(this.active && this.visible)) return false;
		
		for(SelectorButton<T> b : buttons) {
			if(b.mouseClicked(event, doubleClick)) return true;
		}
		
		return false;
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
	}
	
	private static class SelectorButton<T> extends Button {
		
		public T element = null;
		public boolean selected = false;
		public boolean dimColor = false;
		public Identifier icon = null;
		
		@SuppressWarnings("unchecked")
		public SelectorButton(SelectorWidget<T> widget, int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), (b) -> {widget.select(((SelectorButton<T>) b).element);}, (c) -> c.get());
		}
		
		@Override
		protected void extractContents(GuiGraphicsExtractor context, int i, int j, float f) {
			int x = getX();
			int y = getY();
			int width = getWidth();
			int height = getHeight();
			
			Font font = Minecraft.getInstance().font;
			
			// 霓虹选中/悬停样式
			if(selected) {
				PanelRenderer.neonFilled(context, x, y, width, height);
				PanelRenderer.neonBorder(context, x, y, width, height);
			}
			else {
				PanelRenderer.field(context, x, y, width, height);
				if(isHovered()) {
					PanelRenderer.overlay(context, x, y, width, height, WcColors.HOVER_MASK);
					PanelRenderer.neonBorder(context, x, y, width, height);
				}
			}
			
			context.enableScissor(x + 2, y, x + width - 2, y + height);
			
			int xoff = x + 2;
			int iconSize = height - 4;
			
			if(icon != null) {
				context.blit(icon, xoff, y + 2, xoff + iconSize, y + 2 + iconSize, 0.0f, 1.0f, 0.0f, 1.0f);
				xoff += iconSize + 2;
			}
			
			int color = selected ? WcColors.TEXT_ON_NEON : (dimColor ? WcColors.TEXT_DIM : WcColors.TEXT);
			context.text(font, getMessage(), xoff, y + height / 2 - font.lineHeight / 2, color);
			context.disableScissor();
		}
		
	}
	
}
