package dev.evvie.waylandcraft.render;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.evvie.waylandcraft.WindowDisplay;
import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.math.WorldPlane;
import dev.evvie.waylandcraft.shared.RemoteWindowRenderer;
import dev.evvie.waylandcraft.shared.WindowPermission;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * 共享窗口显示类
 * 用于显示远程玩家共享的窗口
 */
public class SharedWindowDisplay {
	
	private final long windowHandle;
	private final String windowTitle;
	private final String ownerName;
	
	// 窗口位置和方向
	private Vec3 pivot = new Vec3(0, 0, 0);
	private Vec3 normal = new Vec3(0, 0, 1);
	private Vec3 down = new Vec3(0, -1, 0);
	
	// 窗口尺寸（framebuffer 尺寸，用于四边形渲染）
	private int width;
	private int height;
	
	// 视觉缩放倍数（与本地 WindowDisplay.viewScale 一致）
	private double viewScale = 1.0;
	// geometry 尺寸（与本地 WindowDisplay.updateGeometry 的 width/height 一致，用于 origin 居中）
	private int geometryWidth;
	private int geometryHeight;
	
	// framebuffer 内容偏移（与本地 WindowDisplay.render 的 xoff/yoff 语义一致）
	private int xoff;
	private int yoff;
	
	// 权限
	private WindowPermission permission = WindowPermission.VIEW;
	
	// 渲染器
	private final RemoteWindowRenderer renderer;
	
	// 是否可见
	private boolean visible = true;
	
	// 锚定距离
	public double anchorDistance = 2.0;
	
	// 上次触发垂直钳制时的窗口尺寸（用于检测 resize 后重新钳制）
	private int lastClampWidth = -1;
	private int lastClampHeight = -1;
	
	// 窗口底部距地面的最小净空（方块），与本地 WindowDisplay 一致
	public static final double GROUND_CLEARANCE = 0.4;
	
	public SharedWindowDisplay(long windowHandle, String windowTitle, String ownerName, RemoteWindowRenderer renderer) {
		this.windowHandle = windowHandle;
		this.windowTitle = windowTitle;
		this.ownerName = ownerName;
		this.renderer = renderer;
	}
	
	/**
	 * 获取窗口句柄
	 */
	public long getWindowHandle() {
		return windowHandle;
	}
	
	/**
	 * 获取窗口标题
	 */
	public String getWindowTitle() {
		return windowTitle;
	}
	
	/**
	 * 获取所有者名称
	 */
	public String getOwnerName() {
		return ownerName;
	}
	
	/**
	 * 设置权限
	 */
	public void setPermission(WindowPermission permission) {
		this.permission = permission;
	}
	
	/**
	 * 获取权限
	 */
	public WindowPermission getPermission() {
		return permission;
	}
	
	/**
	 * 设置可见性
	 */
	public void setVisible(boolean visible) {
		this.visible = visible;
	}
	
	/**
	 * 是否可见
	 */
	public boolean isVisible() {
		return visible;
	}
	
	/**
	 * 更新窗口位置
	 */
	public void updatePosition(int x, int y) {
		// 将屏幕坐标转换为世界坐标
		// 这里简化处理，实际需要根据窗口朝向计算
	}
	
	/**
	 * 设置窗口变换（来自发送者的原始WindowDisplay的pivot/normal/down）
	 */
	public void setTransform(Vec3 pivot, Vec3 normal, Vec3 down) {
		this.pivot = pivot;
		this.normal = normal;
		this.down = down;
	}
	
	/**
	 * 设置所有者世界坐标（窗口显示在该位置）— 兼容旧接口
	 */
	public void setWorldPosition(double x, double y, double z) {
		this.pivot = new Vec3(x, y, z);
	}
	
	/**
	 * 更新窗口大小（原始 framebuffer 尺寸，非缩放）
	 */
	public void updateSize(int width, int height) {
		this.width = width;
		this.height = height;
	}
	
	/**
	 * 设置 framebuffer 内容偏移（xoff/yoff），与本地 WindowDisplay.render 的 bufOffset 对齐
	 */
	public void setBufferOffset(int xoff, int yoff) {
		this.xoff = xoff;
		this.yoff = yoff;
	}
	
	/**
	 * 设置视觉缩放倍数（与本地 WindowDisplay.viewScale 一致）
	 */
	public void setViewScale(double viewScale) {
		this.viewScale = viewScale;
	}
	
	/**
	 * 设置 geometry 尺寸（与本地 WindowDisplay.updateGeometry 的 width/height 一致）
	 */
	public void setGeometrySize(int width, int height) {
		this.geometryWidth = width;
		this.geometryHeight = height;
	}
	
	/**
	 * 获取像素缩放比例 — 与WindowDisplay一致，读取用户设置
	 */
	public float pixelScale() {
		return 1.0f / WaylandCraft.instance.settings.getPixelsPerBlock();
	}
	
	/**
	 * 获取局部X轴方向 — 与本地 WindowDisplay.localX() 一致（含 viewScale）
	 */
	public Vec3 localX() {
		return normal.cross(down).scale(pixelScale() * viewScale);
	}
	
	/**
	 * 获取局部Y轴方向 — 与本地 WindowDisplay.localY() 一致（含 viewScale）
	 */
	public Vec3 localY() {
		return down.scale(pixelScale() * viewScale);
	}
	
	/**
	 * 获取原点位置 — 与本地 WindowDisplay.origin() 一致：
	 * 使用 geometry 尺寸（而非 framebuffer 尺寸）居中，
	 * 保证与本地窗口在世界上完全对齐。
	 */
	public Vec3 origin() {
		int w = geometryWidth > 0 ? geometryWidth : width;
		int h = geometryHeight > 0 ? geometryHeight : height;
		return pivot.add(localX().scale(-w/2)).add(localY().scale(-h/2));
	}
	
	/**
	 * 获取世界平面
	 */
	public WorldPlane getPlane() {
		return new WorldPlane(origin(), localX(), localY(), normal);
	}
	
	/**
	 * 旋转窗口
	 */
	public void rotate(Vec3 normal, Vec3 down) {
		this.normal = normal;
		this.down = down;
	}
	
	/**
	 * 移动原点
	 */
	public void moveOrigin(Vec3 pos) {
		pivot = pos.add(localX().scale(width/2)).add(localY().scale(height/2));
	}
	
	/**
	 * 锚定到位置和视角
	 */
	public void anchorToPosView(Vec3 pos, Vec3 look, Vec3 up) {
		this.pivot = pos.add(look.scale(this.anchorDistance));
		this.rotate(look.reverse(), up.reverse());
	}
	
	/**
	 * 锚定到相机
	 */
	public void anchorToCamera(Camera camera) {
		anchorToPosView(camera.position(), new Vec3(camera.forwardVector()), new Vec3(camera.upVector()));
	}
	
	/**
	 * 调整锚定距离
	 */
	public void adjustAnchorDistance(double delta) {
		this.anchorDistance = Math.clamp(this.anchorDistance + delta * 0.1d, 0.5d, 20d);
	}
	
	/**
	 * 垂直约束：与本地 WindowDisplay.clampVertical() 一致 —
	 * 法线水平化、down=(0,-1,0)、窗口底部不低于地面 GROUND_CLEARANCE 格。
	 */
	public void clampVertical() {
		Vec3 horiz = new Vec3(normal.x, 0, normal.z);
		if(horiz.lengthSqr() < 1e-6) horiz = new Vec3(0, 0, 1);
		this.normal = horiz.normalize();
		this.down = new Vec3(0, -1, 0);
		
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if(mc.level != null) {
			int w = geometryWidth > 0 ? geometryWidth : width;
			int h = geometryHeight > 0 ? geometryHeight : height;
			int groundY = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(pivot.x), (int) Math.floor(pivot.z));
			double halfHeight = (h / 2.0) * pixelScale() * viewScale;
			double minY = groundY + GROUND_CLEARANCE + halfHeight;
			if(pivot.y < minY) pivot = new Vec3(pivot.x, minY, pivot.z);
		}
	}
	
	/**
	 * 窗口分辨率变化后自动重新执行垂直钳制（尺寸变化才触发）。
	 */
	public void clampIfResized() {
		int w = geometryWidth > 0 ? geometryWidth : width;
		int h = geometryHeight > 0 ? geometryHeight : height;
		if(w != lastClampWidth || h != lastClampHeight) {
			lastClampWidth = w;
			lastClampHeight = h;
			clampVertical();
		}
	}
	
	/**
	 * 渲染共享窗口 — 与WindowDisplay.render()完全相同的渲染逻辑
	 * 使用renderFramebufferTexture（同一套WINDOW_CUTOUT/WINDOW_TRANSLUCENT管线）
	 */
	public void render(LevelRenderContext ctx) {
		if(!visible) return;
		if(!renderer.hasTexture(windowHandle)) return;
		
		Identifier textureLocation = renderer.getTextureLocation_obj(windowHandle);
		if(textureLocation == null) return;
		
		// 始终使用原始 framebuffer 尺寸（与本地 WindowDisplay 一致），
		// 纹理（可能被发送端缩放）通过 UV 0..1 拉伸到整个四边形。
		// 若用纹理尺寸渲染，发送端 scale<1 时窗口会变小 → 与本地不一致。
		int renderWidth = this.width;
		int renderHeight = this.height;
		if(renderWidth <= 0 || renderHeight <= 0) {
			// 兜底：纹理尺寸
			int[] dims = renderer.getTextureDimensions(windowHandle);
			if(dims != null && dims[0] > 0 && dims[1] > 0) {
				renderWidth = dims[0];
				renderHeight = dims[1];
			} else {
				return;
			}
		}
		
		// 与WindowDisplay.render()完全一致的向量计算
		Vec3 localX = localX();
		Vec3 localY = localY();

		Vec3 cameraPos = ctx.levelState().cameraRenderState.pos;
		Vec3 originRel = origin().subtract(cameraPos);

		// framebuffer 内容偏移（xoff/yoff），与本地 WindowDisplay.render 的 bufOffset 一致
		Vec3 bufOffset = localX.scale(-xoff).add(localY.scale(-yoff));

		Vec3 tl = bufOffset;
		Vec3 bl = bufOffset.add(localY.scale(renderHeight));
		Vec3 br = bl.add(localX.scale(renderWidth));
		Vec3 tr = tl.add(localX.scale(renderWidth));
		
		PoseStack poseStack = ctx.poseStack();
		poseStack.pushPose();
		poseStack.translate(originRel.x, originRel.y, originRel.z);
		// 使用V-flip版本的渲染管线（远程纹理是top-down的，需要翻转V坐标）
		RenderUtils.renderRemoteFramebufferTexture(textureLocation, poseStack, ctx.submitNodeCollector(), true, tl, bl, br, tr);
		poseStack.popPose();
	}
	
	/**
	 * 窗口是否有效
	 */
	public boolean isValid() {
		return visible && renderer.hasTexture(windowHandle);
	}
	
	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(!(obj instanceof SharedWindowDisplay)) return false;
		SharedWindowDisplay other = (SharedWindowDisplay) obj;
		return windowHandle == other.windowHandle;
	}
	
	@Override
	public int hashCode() {
		return Long.hashCode(windowHandle);
	}
}
