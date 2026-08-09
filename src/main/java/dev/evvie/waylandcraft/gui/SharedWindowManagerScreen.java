package dev.evvie.waylandcraft.gui;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.gui.theme.PanelRenderer;
import dev.evvie.waylandcraft.gui.theme.WcColors;
import dev.evvie.waylandcraft.gui.theme.WcTheme;
import dev.evvie.waylandcraft.gui.widgets.NeonButton;
import dev.evvie.waylandcraft.gui.widgets.WindowViewportWidget;
import dev.evvie.waylandcraft.network.SharedWindowClientHandler;
import dev.evvie.waylandcraft.network.SharedWindowClientHandler.WindowInfo;
import dev.evvie.waylandcraft.shared.WindowPermission;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 共享窗口管理界面 —— 科幻风格重写。
 *
 * 左侧：远程窗口列表（霓虹高亮 + 权限色点）
 * 右侧：选中窗口的实时画面预览（WindowViewportWidget 组件复用远程纹理）
 * 底部：霓虹操作按钮
 */
public class SharedWindowManagerScreen extends Screen {

	private static final int LIST_WIDTH = 280;
	private static final int LIST_HEIGHT = 220;
	private static final int PREVIEW_WIDTH = 320;
	private static final int PREVIEW_HEIGHT = 220;
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 22;

	private final WaylandCraft mod;

	// 窗口列表
	private List<WindowInfo> windowList = new ArrayList<>();
	private int selectedWindow = -1;
	private int scrollOffset = 0;

	// 搜索框
	private EditBox searchBox;

	// 按钮
	private NeonButton subscribeButton;
	private NeonButton unsubscribeButton;
	private NeonButton refreshButton;
	private NeonButton closeButton;

	// 预览组件
	private WindowViewportWidget preview;

	public SharedWindowManagerScreen(WaylandCraft mod) {
		super(Component.translatable("waylandcraft.screen.shared_windows"));
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();

		int centerX = this.width / 2;
		int centerY = this.height / 2;
		int listX = centerX - LIST_WIDTH - 20;
		int listY = centerY - LIST_HEIGHT / 2 - 20;

		// 搜索框
		this.searchBox = new EditBox(this.font, listX, listY - 25, LIST_WIDTH, 20,
			Component.translatable("waylandcraft.screen.search"));
		this.searchBox.setResponder(this::onSearchChanged);
		this.addWidget(this.searchBox);

		// 预览组件（远程窗口画面复用）
		int previewX = centerX + 20;
		this.preview = new WindowViewportWidget(previewX, listY, PREVIEW_WIDTH, PREVIEW_HEIGHT);
		this.addRenderableWidget(this.preview);

		// 按钮
		int buttonY = listY + LIST_HEIGHT + 10;

		this.subscribeButton = new NeonButton(listX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
			Component.translatable("waylandcraft.screen.subscribe"), () -> subscribeSelected());
		this.unsubscribeButton = new NeonButton(listX + BUTTON_WIDTH + 10, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
			Component.translatable("waylandcraft.screen.unsubscribe"), () -> unsubscribeSelected());
		this.refreshButton = new NeonButton(listX + (BUTTON_WIDTH + 10) * 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
			Component.translatable("waylandcraft.screen.refresh"), () -> refreshWindowList());

		int closeX = width / 2 - 55;
		this.closeButton = new NeonButton(closeX, buttonY + 30, 110, BUTTON_HEIGHT,
			Component.translatable("waylandcraft.screen.close"), () -> onClose());

		this.addRenderableWidget(this.subscribeButton);
		this.addRenderableWidget(this.unsubscribeButton);
		this.addRenderableWidget(this.refreshButton);
		this.addRenderableWidget(this.closeButton);

		// 刷新窗口列表
		this.refreshWindowList();
	}

	/**
	 * 刷新窗口列表
	 */
	private void refreshWindowList() {
		this.windowList = SharedWindowClientHandler.getAllRemoteWindows();
		this.selectedWindow = -1;
		this.scrollOffset = 0;
		this.updateButtonStates();
		this.updatePreview();
	}

	/**
	 * 搜索框内容变化
	 */
	private void onSearchChanged(String searchText) {
		// 过滤窗口列表
		if(searchText.isEmpty()) {
			this.windowList = SharedWindowClientHandler.getAllRemoteWindows();
		} else {
			this.windowList = SharedWindowClientHandler.getAllRemoteWindows().stream()
				.filter(info -> info.title().toLowerCase().contains(searchText.toLowerCase()) ||
					info.ownerName().toLowerCase().contains(searchText.toLowerCase()))
				.toList();
		}
		this.selectedWindow = -1;
		this.updateButtonStates();
		this.updatePreview();
	}

	/**
	 * 订阅选中的窗口
	 */
	private void subscribeSelected() {
		if(selectedWindow >= 0 && selectedWindow < windowList.size()) {
			WindowInfo info = windowList.get(selectedWindow);
			SharedWindowClientHandler.requestWindowRegister(info.windowHandle(), info.title());
		}
	}

	/**
	 * 取消订阅选中的窗口
	 */
	private void unsubscribeSelected() {
		if(selectedWindow >= 0 && selectedWindow < windowList.size()) {
			WindowInfo info = windowList.get(selectedWindow);
			SharedWindowClientHandler.requestWindowUnregister(info.windowHandle());
		}
	}

	/**
	 * 更新按钮状态
	 */
	private void updateButtonStates() {
		boolean hasSelection = selectedWindow >= 0 && selectedWindow < windowList.size();
		this.subscribeButton.active = hasSelection;
		this.unsubscribeButton.active = hasSelection;
	}

	/**
	 * 更新预览组件（复用 WindowViewportWidget）
	 */
	private void updatePreview() {
		if(selectedWindow >= 0 && selectedWindow < windowList.size()) {
			WindowInfo info = windowList.get(selectedWindow);
			Identifier texture = mod.remoteWindowRenderer.getTextureLocation_obj(info.windowHandle());
			int w = info.width() > 0 ? info.width() : 1280;
			int h = info.height() > 0 ? info.height() : 720;
			if(texture != null) {
				this.preview.setRemoteWindow(texture, w, h, Component.literal(info.title()), false);
			} else {
				this.preview.setRemoteWindow(null, w, h, Component.literal(info.title()), false);
			}
		} else {
			this.preview.clear();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		// 深空背景
		context.fill(0, 0, width, height, WcColors.BG_BASE);

		int centerX = this.width / 2;
		int centerY = this.height / 2;
		int listX = centerX - LIST_WIDTH - 20;
		int listY = centerY - LIST_HEIGHT / 2 - 20;

		// 主面板
		PanelRenderer.panel(context, listX - 12, listY - 38, centerX + 20 - (listX - 12) + PREVIEW_WIDTH + 12, LIST_HEIGHT + 88);

		// 标题（霓虹）
		Component styledTitle = title.copy().withColor(WcColors.CYAN);
		context.text(this.font, styledTitle, centerX - this.font.width(styledTitle) / 2, listY - 50, WcColors.CYAN);

		// 搜索框
		this.searchBox.extractRenderState(context, mouseX, mouseY, delta);

		// 列表底
		PanelRenderer.field(context, listX - 1, listY - 1, LIST_WIDTH + 2, LIST_HEIGHT + 2);

		// 窗口列表
		renderWindowList(context, listX, listY, mouseX, mouseY);

		// 预览（组件渲染）
		this.preview.extractRenderState(context, mouseX, mouseY, delta);

		// 选中窗口详细信息
		if(selectedWindow >= 0 && selectedWindow < windowList.size()) {
			renderWindowDetails(context, listX, listY + LIST_HEIGHT + 38);
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	/**
	 * 渲染窗口列表
	 */
	private void renderWindowList(GuiGraphicsExtractor context, int x, int y, int mouseX, int mouseY) {
		int itemHeight = WcTheme.LIST_ROW_HEIGHT;
		int visibleItems = LIST_HEIGHT / itemHeight;

		for(int i = 0; i < visibleItems && i + scrollOffset < windowList.size(); i++) {
			int index = i + scrollOffset;
			WindowInfo info = windowList.get(index);

			int itemY = y + i * itemHeight;
			boolean isSelected = index == selectedWindow;
			boolean isHovered = mouseX >= x && mouseX < x + LIST_WIDTH &&
				mouseY >= itemY && mouseY < itemY + itemHeight;

			// 选中/悬停底
			if(isSelected) {
				PanelRenderer.neonFilled(context, x, itemY, LIST_WIDTH, itemHeight);
			} else if(isHovered) {
				context.fill(x, itemY, x + LIST_WIDTH, itemY + itemHeight, WcColors.HOVER_MASK);
			}

			// 窗口标题
			String title = info.title();
			if(title.length() > 30) {
				title = title.substring(0, 27) + "...";
			}
			int textColor = isSelected ? WcColors.TEXT_ON_NEON : WcColors.TEXT;
			context.text(this.font, title, x + WcTheme.LIST_ROW_PAD, itemY + (itemHeight - font.lineHeight) / 2, textColor);

			// 权限色点
			int permissionColor = getPermissionColor(info.permission());
			context.fill(x + LIST_WIDTH - 18, itemY + itemHeight / 2 - 3, x + LIST_WIDTH - 10, itemY + itemHeight / 2 + 3, permissionColor);
		}

		// 霓虹滚动条
		if(windowList.size() > visibleItems) {
			int scrollbarHeight = Math.max(20, (int)((float)visibleItems / windowList.size() * LIST_HEIGHT));
			int scrollbarY = y + (int)((float)scrollOffset / Math.max(1, windowList.size() - visibleItems) * (LIST_HEIGHT - scrollbarHeight));
			context.fill(x + LIST_WIDTH - 3, scrollbarY, x + LIST_WIDTH - 1, scrollbarY + scrollbarHeight, WcColors.CYAN);
		}
	}

	/**
	 * 渲染窗口详细信息
	 */
	private void renderWindowDetails(GuiGraphicsExtractor context, int x, int y) {
		WindowInfo info = windowList.get(selectedWindow);

		int detailW = LIST_WIDTH;
		int detailH = 64;
		PanelRenderer.field(context, x, y, detailW, detailH);

		context.text(this.font, "名称: " + truncate(info.title(), 26), x + 8, y + 5, WcColors.TEXT);
		context.text(this.font, "所有者: " + truncate(info.ownerName(), 26), x + 8, y + 17, WcColors.TEXT_DIM);
		context.text(this.font, "权限: " + info.permission().name(), x + 8, y + 29, getPermissionColor(info.permission()));
		context.text(this.font, "尺寸: " + info.width() + "x" + info.height(), x + 8, y + 41, WcColors.TEXT_DIM);
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max - 3) + "..." : s;
	}

	/**
	 * 获取权限颜色
	 */
	private int getPermissionColor(WindowPermission permission) {
		return switch(permission) {
			case NONE -> WcColors.DANGER;
			case VIEW -> WcColors.WARNING;
			case INTERACT -> WcColors.SUCCESS;
			case CONTROL -> WcColors.CYAN;
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
		if(event.button() == 0) {
			double mouseX = event.x();
			double mouseY = event.y();
			int centerX = this.width / 2;
			int centerY = this.height / 2;
			int listX = centerX - LIST_WIDTH - 20;
			int listY = centerY - LIST_HEIGHT / 2 - 20;

			// 检查是否点击了列表项
			if(mouseX >= listX && mouseX < listX + LIST_WIDTH &&
				mouseY >= listY && mouseY < listY + LIST_HEIGHT) {
				int itemHeight = WcTheme.LIST_ROW_HEIGHT;
				int clickedIndex = (int)((mouseY - listY) / itemHeight) + scrollOffset;

				if(clickedIndex >= 0 && clickedIndex < windowList.size()) {
					this.selectedWindow = clickedIndex;
					this.updateButtonStates();
					this.updatePreview();
				}
			}
		}

		return super.mouseClicked(event, isDoubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int maxScroll = Math.max(0, windowList.size() - LIST_HEIGHT / WcTheme.LIST_ROW_HEIGHT);
		this.scrollOffset = (int)Math.max(0, Math.min(maxScroll, this.scrollOffset - verticalAmount));
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
