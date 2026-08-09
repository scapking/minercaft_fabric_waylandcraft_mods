package dev.evvie.waylandcraft.grabs;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import dev.evvie.waylandcraft.WindowDisplay;
import dev.evvie.waylandcraft.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.grabs.PointerGrabMap.ImplicitGrab;
import dev.evvie.waylandcraft.utils.CursorShape;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public class MoveGrab extends PointerGrab {
	
	private final WindowDisplay window;
	private final Vec3 initialSurfaceLocal;
	
	public MoveGrab(ImplicitGrab implicit) {
		super(implicit.button());
		this.window = implicit.window();
		this.initialSurfaceLocal = implicit.startSurfaceLocal();
	}
	
	@Override
	public void init() throws GrabDroppedException {
	}
	
	@Override
	public void release(boolean force) throws GrabDroppedException {
	}
	
	@Override
	public void moveWorld(Vec3 pos, Vec3 view, Vec3 up, float yRot, float xRot) throws GrabDroppedException {
		if(!window.isValid()) this.drop();
		
		wlc.cursorShape = CursorShape.ALL_RESIZE;
		
		DisplayHitResult hitResult = window.intersect(pos, view);
		if(hitResult == null) return;
		
		Vec3 diff = hitResult.surfaceLocalOrigin.subtract(initialSurfaceLocal);
		window.pivot = window.pivot.add(window.localX().scale(diff.x).add(window.localY().scale(diff.y)));
	}
	
	@Override
	public void onScroll(double scrollX, double scrollY) throws GrabDroppedException {
		if(!window.isValid()) this.drop();
		
		boolean ctrl = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL);
		boolean alt = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_ALT);
		
		if(ctrl && !alt) {
			// Ctrl+滚轮 = 绕法线轴旋转（与悬停时一致）
			window.rotateBy(scrollY * 0.1);
		} else if(alt && !ctrl) {
			// Alt+滚轮 = 调整缩放
			window.adjustScale(scrollY);
		} else {
			// 普通滚轮 = 沿法线方向前后移动
			window.pivot = window.pivot.add(window.normal().scale(scrollY * 0.1));
		}
	}
	
}
