package dev.evvie.waylandcraft.gui;

import java.util.ArrayList;
import java.util.HashSet;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.Size;
import dev.evvie.waylandcraft.render.RenderUtils;
import dev.evvie.waylandcraft.render.WindowFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 极简窗口管理屏：纯命令行模式下唯一保留的 GUI。
 *
 * 设计要点：
 * - 不透明纯色背景（类似 Linux 终端，无透明）
 * - 窗口以 1:1 物理像素渲染 framebuffer（与原版渲染方式一致）
 * - 窗口四周只有 1px 细边框（类似无边框手机屏幕的微边缘），
 *   聚焦窗口亮边框、非聚焦暗边框，不遮挡窗口内容
 * - 无任何按钮/列表/装饰；所有操作通过 /wl 命令完成
 */
public class WindowManagerScreen extends Screen {
	
	private WaylandCraft wlc;
	
	/** 纯色背景（不透明，接近终端深色） */
	private static final int BG_COLOR = 0xFF000000;
	/** 聚焦窗口边框（亮） */
	private static final int BORDER_FOCUSED = 0xFFFFFFFF;
	/** 非聚焦窗口边框（暗） */
	private static final int BORDER_UNFOCUSED = 0xFF505050;
	/** 边框宽度（物理像素） */
	private static final int BORDER_WIDTH = 1;
	
	private int guiScale;
	
	private boolean captureModeEnabled = false;
	
	private WLCToplevel focused = null;
	private WLCToplevel lastFocused = null;
	
	// All window elements currently displayed, sorted by depth from bottom-most (root) to top-most (last leaf)
	public ArrayList<WindowElement> windows = new ArrayList<WindowElement>();
	
	private ImplicitGrab implicitGrab = null;
	
	public WindowManagerScreen(WaylandCraft wlc) {
		super(Component.literal("Window Manager"));
		this.wlc = wlc;
	}
	
	@Override
	protected void init() {
		wlc.bridge.activateKeyboard();
	}
	
	@Override
	public boolean isPauseScreen() {
		return false;
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int i, int j, float f) {
		super.extractBlurredBackground(context);
		
		// 不透明纯色背景
		context.fill(0, 0, width, height, BG_COLOR);
		
		guiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
		wlc.bridge.setOutputBounds(width * guiScale, height * guiScale);
		
		WLCToplevel renderToplevel = null;
		lastFocused = focused;
		
		// Update focus to toplevel that has highest focus priority
		focused = wlc.bridge.getMostRecentFocus();
		wlc.bridge.focusSurface(focused);
		renderToplevel = focused;
		
		windows.clear();
		
		float guiScaleF = (float) Minecraft.getInstance().getWindow().getGuiScale();
		Matrix3x2fStack poseStack = context.pose();
		poseStack.pushMatrix();
		poseStack.scale(1 / guiScaleF, 1 / guiScaleF);
		
		if(renderToplevel != null) {
			prepareToplevel(renderToplevel);
			
			for(WindowElement element : windows) {
				WindowFramebuffer buf = element.window.framebuffer;
				if(buf == null) continue;
				
				int x = (int) element.x - buf.getXOff();
				int y = (int) element.y - buf.getYOff();
				int w = buf.getWidth();
				int h = buf.getHeight();
				
				// 细边框（物理像素，画在窗口外圈，不遮挡窗口内容）
				boolean isFocused = element.window == renderToplevel;
				int borderColor = isFocused ? BORDER_FOCUSED : BORDER_UNFOCUSED;
				int b = BORDER_WIDTH;
				context.fill(x - b, y - b, x + w + b, y, borderColor);         // top
				context.fill(x - b, y + h, x + w + b, y + h + b, borderColor); // bottom
				context.fill(x - b, y, x, y + h, borderColor);                 // left
				context.fill(x + w, y, x + w + b, y + h, borderColor);         // right
				
				RenderUtils.renderFramebuffer2D(context, buf, x, y, w, h);
			}
		}
		
		poseStack.popMatrix();
		
		super.extractRenderState(context, i, j, f);
	}
	
	@Override
	public void extractBackground(GuiGraphicsExtractor guiGraphics, int i, int j, float f) {
	}
	
	private HoveredSurface surfaceUnderPointer(double x, double y) {
		for(int i = windows.size() - 1; i >= 0; i--) {
			WindowElement element = windows.get(i);
			
			float sx = (float) x - element.x;
			float sy = (float) y - element.y;
			
			for(WLCSurface surface = element.window.getSurfaceTreeLast(); surface != null; surface = surface.getPrevChild()) {
				float rx = sx - surface.xSubpos;
				float ry = sy - surface.ySubpos;
				
				int width = surface.width();
				int height = surface.height();
				
				if(rx < 0 || ry < 0 || rx > width || ry > height) {
					continue;
				}
				
				if(!surface.isAlive()) continue;
				
				if(wlc.bridge.inputRegionContains(surface, rx, ry)) {
					return new HoveredSurface(surface, rx, ry);
				}
			}
		}
		
		return null;
	}
	
	@Override
	public void mouseMoved(double x, double y) {
		x *= guiScale;
		y *= guiScale;
		
		HoveredSurface hovered = surfaceUnderPointer(x, y);
		
		if(implicitGrab != null && !implicitGrab.surface.isAlive()) implicitGrab = null;
		
		if(implicitGrab == null) {
			if(hovered != null) wlc.bridge.sendMotionRefocus(hovered.surface, hovered.rx, hovered.ry);
			else wlc.bridge.sendMotionOutside();
		}
		else {
			for(WindowElement elem : windows) {
				WLCSurface surface;
				for(surface = elem.window.getSurfaceTree(); surface != null && surface != implicitGrab.surface; surface = surface.getNextChild()) {}
				if(surface == implicitGrab.surface) {
					// Surface was found in this window elements' surface tree
					
					float rx = (float) x - elem.x - surface.xSubpos;
					float ry = (float) y - elem.y - surface.ySubpos;
					
					wlc.bridge.sendMotion(rx, ry);
					break;
				}
			}
		}
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(super.mouseClicked(event, doubleClick)) return true;
		
		double x = event.x() * guiScale;
		double y = event.y() * guiScale;
		
		HoveredSurface hovered = surfaceUnderPointer(x, y);
		if(implicitGrab == null && hovered != null) {
			implicitGrab = new ImplicitGrab(hovered.surface);
		}
		
		if(implicitGrab != null && !implicitGrab.pressedMouseButtons.contains(event.button())) {
			implicitGrab.pressedMouseButtons.add(event.button());
			wlc.bridge.sendButton(0x110 + event.button(), 1);
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if(super.mouseReleased(event)) return true;
		
		if(implicitGrab != null && implicitGrab.pressedMouseButtons.contains(event.button())) {
			implicitGrab.pressedMouseButtons.remove(event.button());
			wlc.bridge.sendButton(0x110 + event.button(), 0);
			
			if(implicitGrab.pressedMouseButtons.isEmpty()) implicitGrab = null;
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean keyPressed(KeyEvent event) {
		if(event.key() == GLFW.GLFW_KEY_ESCAPE && !captureModeEnabled) {
			this.onClose();
			return true;
		}
		
		if(event.key() == GLFW.GLFW_KEY_Q && event.modifiers() == GLFW.GLFW_MOD_ALT) {
			captureModeEnabled = !captureModeEnabled;
			return true;
		}
		
		// Forward key press to current window
		if(focused != null) {
			int scancode = WaylandCraft.correctScancode(event.scancode());
			wlc.bridge.pressKey(scancode);
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean keyReleased(KeyEvent event) {
		if(super.keyReleased(event)) return true;
		
		if(focused != null) {
			int scancode = WaylandCraft.correctScancode(event.scancode());
			wlc.bridge.releaseKey(scancode);
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if(super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
		
		mouseX *= guiScale;
		mouseY *= guiScale;
		
		HoveredSurface hovered = surfaceUnderPointer(mouseX, mouseY);
		
		if(hovered != null) {
			wlc.bridge.sendScroll(0, -scrollY);
			wlc.bridge.sendScroll(1, -scrollX);
			return true;
		}
		
		return false;
	}
	
	@Override
	public void removed() {
		if(implicitGrab != null) {
			implicitGrab.pressedMouseButtons.forEach((button) -> wlc.bridge.sendButton(0x110 + button, 0));
			implicitGrab = null;
		}
		wlc.bridge.deactivateKeyboard();
	}
	
	private void prepareToplevel(WLCToplevel toplevel) {
		float x;
		float y;
		
		if(!toplevel.fullscreen || !captureModeEnabled) {
			x = Math.max(0, width * guiScale / 2.0f - toplevel.geometry.width() / 2.0f);
			y = Math.max(0, height * guiScale / 2.0f - toplevel.geometry.height() / 2.0f);
		}
		else {
			x = 0;
			y = 0;
		}
		
		x -= toplevel.geometry.x();
		y -= toplevel.geometry.y();
		
		windows.add(new WindowElement(toplevel, x, y));
		
		WindowTree tree = WindowTree.constructTree(wlc.bridge, toplevel);
		preparePopupTree(tree, x, y);
	}
	
	private void preparePopupTree(WindowTree tree, float x, float y) {
		if(tree.window instanceof WLCPopup) {
			WLCPopup popup = (WLCPopup) tree.window;
			
			x += popup.getParent().geometry.x();
			y += popup.getParent().geometry.y();
			
			x += popup.offsetX;
			y += popup.offsetY;
			
			x -= popup.geometry.x();
			y -= popup.geometry.y();
			
			windows.add(new WindowElement(popup, x, y));
		}
		
		for(WindowTree child : tree.children) {
			preparePopupTree(child, x, y);
		}
	}
	
	public static class WindowElement {
		
		public WLCAbstractWindow window;
		public float x;
		public float y;
		
		public WindowElement(WLCAbstractWindow window, float x, float y) {
			this.window = window;
			this.x = x;
			this.y = y;
		}
		
	}
	
	public static class WindowTree {
		
		public WLCAbstractWindow window;
		public ArrayList<WindowTree> children;
		
		private WindowTree(WLCAbstractWindow window) {
			this.window = window;
			this.children = new ArrayList<WindowTree>();
		}
		
		public static WindowTree constructTree(WaylandCraftBridge bridge, WLCToplevel toplevel) {
			WindowTree tree = new WindowTree(toplevel);
			
			for(WLCPopup popup : bridge.getMappedPopups()) {
				WLCAbstractWindow root;
				for(root = popup; !(root instanceof WLCToplevel); root = ((WLCPopup) root).getParent()) {}
				if(root != toplevel) continue;
				addRecursive(tree, popup);
			}
			
			return tree;
		}
		
		private static WindowTree addRecursive(WindowTree tree, WLCPopup popup) {
			WLCAbstractWindow parentWindow = popup.getParent();
			WindowTree parent;
			if(parentWindow instanceof WLCPopup) {
				parent = addRecursive(tree, (WLCPopup) parentWindow);
			}
			else {
				parent = tree;
			}
			
			for(WindowTree child : parent.children) {
				if(child.window == popup) return child;
			}
			
			WindowTree child = new WindowTree(popup);
			parent.children.add(child);
			return child;
		}
		
	}
	
	private static record HoveredSurface(WLCSurface surface, float rx, float ry) {}
	
	private static class ImplicitGrab {
		
		public final WLCSurface surface;
		public HashSet<Integer> pressedMouseButtons = new HashSet<Integer>();
		
		public ImplicitGrab(WLCSurface surface) {
			this.surface = surface;
		}
		
	}
	
}
