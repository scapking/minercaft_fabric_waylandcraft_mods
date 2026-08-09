package dev.evvie.waylandcraft.gui;

import java.util.List;
import java.util.function.Consumer;

import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 分类选择器 —— 霓虹图标按钮列。
 */
public class CategorySelectorWidget extends AbstractWidget {
	
	private int selected = -1;
	private List<Entry> entries;
	private int elementSize;
	private Consumer<Integer> selectAction;
	
	public CategorySelectorWidget(Component component, Consumer<Integer> selectAction, List<Entry> entries) {
		super(0, 0, 0, 0, component);
		this.selectAction = selectAction;
		this.entries = entries;
	}
	
	public void setElementSize(int s) {
		this.elementSize = s;
	}
	
	public int getSelected() {
		return selected;
	}
	
	public void select(int idx) {
		selected = idx;
		selectAction.accept(idx);
	}
	
	public void unselect() {
		selected = -1;
	}
	
	@Override
	public ComponentPath nextFocusPath(FocusNavigationEvent focusNavigationEvent) {
		return null;
	}
	
	private int elementsPerColumn() {
		return getHeight() / elementSize;
	}
	
	private int idxPosX(int idx) {
		return getX() + (entries.size() - 1) / elementsPerColumn() * elementSize - idx / elementsPerColumn() * elementSize;
	}
	
	private int idxPosY(int idx) {
		return getY() + (idx % elementsPerColumn()) * elementSize;
	}
	
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
		for(int i = 0; i < entries.size(); i++) {
			int bx = idxPosX(i);
			int by = idxPosY(i);
			boolean hovered = mouseX > bx && mouseY > by && mouseX < bx + elementSize && mouseY < by + elementSize;
			boolean isSel = i == selected;
			
			// 底
			if(isSel) {
				PanelRenderer.neonFilled(context, bx, by, elementSize, elementSize);
				PanelRenderer.neonBorder(context, bx, by, elementSize, elementSize);
			}
			else {
				PanelRenderer.field(context, bx, by, elementSize, elementSize);
				if(hovered) PanelRenderer.overlay(context, bx, by, elementSize, elementSize, WcColors.HOVER_MASK);
			}
			
			int iconPad = 2;
			context.blit(entries.get(i).icon, bx + iconPad, by + iconPad, bx + elementSize - iconPad, by + elementSize - iconPad, 0.0f, 1.0f, 0.0f, 1.0f);
			
			if(hovered) {
				context.setTooltipForNextFrame(entries.get(i).title, mouseX, mouseY);
			}
		}
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for(int i = 0; i < entries.size(); i++) {
			int bx = idxPosX(i);
			int by = idxPosY(i);
			
			if(event.x() > bx && event.y() > by && event.x() < bx + elementSize && event.y() < by + elementSize) {
				select(i);
				return true;
			}
		}
		
		return false;
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
	}
	
	public static record Entry(Component title, Identifier icon) {}
	
}
