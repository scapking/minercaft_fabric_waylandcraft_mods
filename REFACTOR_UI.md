# ⚠️ 已废弃 / DEPRECATED

> 本文档描述的是 **Sci-Fi Design System UI 重构规范**。该科幻风格 UI 已在 **v0.2.0**（纯 CLI 模式）中整体移除，回归原版渲染风格。本文档仅作历史留档，**不再适用**。当前 UI 行为请见 [README_ZH.md](README_ZH.md)。

---

# WaylandCraft UI 重构规范（Sci-Fi Design System）

## 目标

1. **统一**：消除 3 套并存的 UI 风格（原版 MC 按钮贴图 / slot_thingy 贴图 / 纯 fill 手绘）。
2. **科幻前沿**：深空底色 + 霓虹青主色 + 圆角面板 + 辉光聚焦，全组件统一语言。
3. **规范**：颜色/间距/圆角/尺寸全部收敛到 `gui/theme/` 下的 Design Tokens。
4. **组件复用**：窗口画面渲染抽成可复用组件（`WindowViewportWidget`），
   WindowManagerScreen / HUD / 共享窗口预览共用同一套逻辑。

## 目录结构

```
gui/
  theme/
    WcColors.java      # 色板（0xAARRGGBB，禁止 java.awt.Color）
    WcTheme.java       # 尺寸/间距/圆角 tokens
    WindowLayout.java  # 窗口画面适配布局（纯逻辑，可单测）
    PanelRenderer.java # 9-patch 圆角面板/边框/辉光绘制
  widgets/
    NeonButton.java    # 霓虹按钮
    NeonToggle.java    # 霓虹开关（替代 SettingsWidget.BooleanControlElement）
    WindowViewportWidget.java  # 窗口画面复用组件（核心）
  WindowManagerScreen.java
  AppLauncherScreen.java
  SharedWindowManagerScreen.java
  WaylandCraftSettingsScreen.java
  WaylandHudRenderer.java
```

## 规范

- 颜色一律用 `WcColors` 常量；布局间距用 `WcTheme`；禁止魔法数字。
- 圆角用 9-patch sprite（`textures/gui/sprites/*_9.png`，border=6）。
- 文本主色 `TEXT`，次色 `TEXT_DIM`，禁用 `TEXT_DISABLED`。
- 聚焦/选中一律用霓虹青 `BORDER_FOCUS` + `CYAN_GLOW` 辉光。
- 危险动作红色，成功绿色，警告琥珀。

## 窗口画面组件（WindowViewportWidget）

职责：
- 输入：本地 `WindowFramebuffer` 或远程纹理 `Identifier` + 尺寸 + 标题 + 图标。
- 输出：统一渲染「标题栏 + 边框 + 内容画面（contain 适配）」。
- 复用点：
  - WindowManagerScreen 的窗口列表/聚焦窗口
  - HUD 的 pinned 窗口
  - SharedWindowManagerScreen 的窗口预览

## 验收

- [x] `./gradlew test` 通过（纯逻辑测试：WindowLayout / WcColors，16 tests）
- [x] `./gradlew build` 产出可用的 mod jar（native .so 已打包，41MB）
- [x] 全部 5 个 Screen/HUD 迁移到设计系统，无 java.awt.Color 残留
- [x] 窗口画面组件化：WindowViewportWidget（本地 framebuffer / 远程纹理双数据源，
      HUD pinned 已用静态 render() 接入，SharedWindowManagerScreen 已接入远程预览）
- [x] 9-patch 纹理 + nine_slice mcmeta（panel/field/neon_border/neon_filled/titlebar）
- [x] 预览图：tools/gen_preview.py → tools/preview/*.png

## 后续（可选）

- WindowManagerScreen 工作区窗口改为组件化卡片视图（当前保留 1:1 工作区语义）
- NeonEditBox（替换原版 EditBox 样式）
- 运行时 UI 测试（需要真实 Wayland 环境）

