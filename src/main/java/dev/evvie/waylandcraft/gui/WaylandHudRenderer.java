package dev.evvie.waylandcraft.gui;

import java.util.Calendar;

import org.joml.Matrix3x2fStack;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WaylandCraft.KeyboardCaptureMode;
import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.IconSurface;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.desktop.DesktopEntry;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import dev.evvie.waylandcraft.gui.widgets.WindowViewportWidget;
import dev.evvie.waylandcraft.render.RenderUtils;
import dev.evvie.waylandcraft.render.WindowFramebuffer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

public class WaylandHudRenderer {
	
	private WaylandCraft wlc;
	private static final Identifier TIME_DATE = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "time-date");
	private static final Identifier APP_LIST = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "app-list");
	private static final Identifier PINNED_TOPLEVEL = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pinned-toplevel");
	private static final Identifier DND_ICON = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "dnd-icon");
	
	public WaylandHudRenderer(WaylandCraft wlc) {
		this.wlc = wlc;
	}
	
	public void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, TIME_DATE, this::extractTimeDateRenderState);
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, APP_LIST, this::extractAppListRenderState);
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, PINNED_TOPLEVEL, this::extractPinnedToplevelRenderState);
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, DND_ICON, this::extractDNDIconRenderState);
	}
	
	private void extractAppListRenderState(GuiGraphicsExtractor context, DeltaTracker deltaTracker) {
		Font font = Minecraft.getInstance().font;
		int yoff = 30;
		int ystep = font.lineHeight + 2;
		
		if(WaylandCraft.instance.keyboardCaptureMode == KeyboardCaptureMode.CAPTURE) {
			String text = "KEYBOARD CAPTURED [PRESS ESCAPE]";
			drawWarningTag(context, font, text, yoff);
			yoff += ystep;
		}
		else if(WaylandCraft.instance.keyboardCaptureMode == KeyboardCaptureMode.HARD_CAPTURE) {
			String text = "KEYBOARD CAPTURED [PRESS ALT+Q]";
			drawWarningTag(context, font, text, yoff);
			yoff += ystep;
		}
		
		for(WLCToplevel toplevel : WaylandCraft.instance.bridge.getMappedToplevels()) {
			String appID = toplevel.appID;
			DesktopEntry entry = wlc.xdgManager.forAppId(appID);
			
			String name = "<unknown app>";
			if(appID != null) name = appID;
			if(entry != null && entry.name != null) name = entry.name;
			
			Style style = Style.EMPTY;
			int color = WcColors.TEXT;
			
			if(!wlc.hasDisplayFor(toplevel)) {
				color = WcColors.TEXT_DIM;
			}
			if(toplevel == wlc.bridge.getMostRecentFocus()) {
				color = WcColors.CYAN;
			}
			
			int x = context.guiWidth() - font.width(name) - 10;
			// 半透明底
			context.fill(x - 4, yoff - 2, context.guiWidth() - 4, yoff + font.lineHeight + 2, WcColors.PANEL_INSET);
			context.text(font, Component.literal(name).withStyle(style), x, yoff, color, true);
			
			if(entry != null) {
				Identifier icon = entry.getIcon();
				int iconX = x - font.lineHeight - 2;
				int iconY = yoff;
				int iconSize = font.lineHeight;
				if(icon != null) context.blit(icon, iconX, iconY, iconX + iconSize, iconY + iconSize, 0.0f, 1.0f, 0.0f, 1.0f);
			}
			
			yoff += ystep;
		}
	}
	
	private void drawWarningTag(GuiGraphicsExtractor context, Font font, String text, int yoff) {
		int x = context.guiWidth() - font.width(text) - 14;
		// 危险提示条
		context.fill(x - 8, yoff - 3, context.guiWidth() - 6, yoff + font.lineHeight + 3, WcColors.DANGER_DIM);
		context.fill(x - 2, yoff - 3, x, yoff + font.lineHeight + 3, WcColors.DANGER);
		context.text(font, text, x, yoff, WcColors.DANGER, true);
	}
	
	private void extractPinnedToplevelRenderState(GuiGraphicsExtractor context, DeltaTracker deltaTracker) {
		int guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
		
		if(wlc.pinnedToplevel != null && !wlc.pinnedToplevel.isAlive()) wlc.pinnedToplevel = null;
		if(wlc.pinnedToplevel != null) {
			WindowFramebuffer buf = wlc.pinnedToplevel.framebuffer;
			if(buf == null) return;
			
			SurfaceGeometry geometry = wlc.pinnedToplevel.geometry;
			
			// 组件化渲染：标题栏 + 画面（0.5 缩放）
			int w = buf.getWidth() / 2;
			int h = buf.getHeight() / 2;
			int x = -buf.getXOff() - geometry.x();
			int y = -buf.getYOff() - geometry.y();
			
			DesktopEntry entry = wlc.xdgManager.forAppId(wlc.pinnedToplevel.appID);
			Component title = Component.literal(wlc.pinnedToplevel.title != null ? wlc.pinnedToplevel.title : (wlc.pinnedToplevel.appID != null ? wlc.pinnedToplevel.appID : "pinned"));
			Identifier icon = entry != null ? entry.getIcon() : null;
			
			WindowViewportWidget.render(context, buf, x, y, w, h, title, icon, true);
		}
	}
	
	private void extractDNDIconRenderState(GuiGraphicsExtractor context, DeltaTracker tracker) {
		int guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
		
		IconSurface dndIcon = wlc.bridge.dndIcon;
		if(dndIcon != null && dndIcon.framebuffer != null) {
			WindowFramebuffer buf = dndIcon.framebuffer;
			
			int x = -buf.getXOff();
			int y = -buf.getYOff();
			int w = buf.getWidth();
			int h = buf.getHeight();
			
			Matrix3x2fStack stack = context.pose();
			stack.pushMatrix();
			stack.translate(context.guiWidth() / 2, context.guiHeight() / 2);
			stack.scale(1.0f / guiScale, 1.0f / guiScale);
			RenderUtils.renderFramebuffer2D(context, buf, x, y, w, h);
			stack.popMatrix();
		}
	}
	
	private void extractTimeDateRenderState(GuiGraphicsExtractor context, DeltaTracker deltaTracker) {
		Font font = Minecraft.getInstance().font;
		String datetime = String.format("%1$tF %1$tR", Calendar.getInstance());
		
		int w = font.width(datetime) + 8;
		int x = context.guiWidth() - font.width(datetime) - 6;
		context.fill(x - 4, 0, context.guiWidth(), font.lineHeight + 4, WcColors.PANEL_INSET);
		context.fill(context.guiWidth() - 2, 0, context.guiWidth(), font.lineHeight + 4, WcColors.CYAN_DIM);
		context.text(font, datetime, x, 2, WcColors.TEXT, true);
	}
	
}
