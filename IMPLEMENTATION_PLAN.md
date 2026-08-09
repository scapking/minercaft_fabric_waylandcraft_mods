# ✅ 已完成 / COMPLETED

> 本文档是"多人显示功能"的早期实现计划。相关功能已全部实现并发布（v0.2.0–v0.2.6）。本文档仅作历史留档。当前功能与用法请见 [README_ZH.md](README_ZH.md)。

---

# WaylandCraft 多人显示功能实现计划

## 项目概述
为WaylandCraft添加多人显示功能，允许多个玩家同时查看和交互同一个Wayland窗口。

## 代码风格规范
- Tab缩进
- 类名：PascalCase
- 方法名：camelCase
- 常量：UPPER_SNAKE_CASE
- 包名：dev.evvie.waylandcraft.shared
- 使用Stream API
- Nullable注解
- Profiler性能追踪

## 实现阶段

### 阶段1：服务器端核心 (P0)
1. 创建 `SharedWindowManager` - 窗口注册表管理
2. 创建 `WindowPermission` - 权限枚举
3. 创建 `SharedWindowEntry` - 共享窗口条目

### 阶段2：网络协议 (P0)
1. 创建 `SharedWindowRegisterPayload` - 窗口注册包
2. 创建 `SharedWindowUpdatePayload` - 窗口更新包
3. 创建 `SharedWindowImagePayload` - 图像数据包
4. 创建 `SharedWindowInteractionPayload` - 交互包
5. 创建 `SharedWindowPermissionPayload` - 权限包

### 阶段3：权限管理 (P1)
1. 创建 `PermissionManager` - 权限管理器
2. 实现权限检查和授权逻辑

### 阶段4：客户端渲染 (P1)
1. 创建 `RemoteWindowRenderer` - 远程窗口渲染器
2. 创建 `ImageDecoder` - 图像解码器
3. 创建 `SharedWindowDisplay` - 共享窗口显示

### 阶段5：集成 (P2)
1. 修改 `WaylandCraftCommon` 注册服务端逻辑
2. 修改 `WaylandCraft` 注册客户端逻辑
3. 修改 `WaylandCraftNetworking` 注册新数据包

## 文件结构
```
src/main/java/dev/evvie/waylandcraft/
├── shared/
│   ├── SharedWindowManager.java
│   ├── WindowPermission.java
│   ├── SharedWindowEntry.java
│   ├── PermissionManager.java
│   └── RemoteWindowRenderer.java
├── network/
│   ├── SharedWindowRegisterPayload.java
│   ├── SharedWindowUpdatePayload.java
│   ├── SharedWindowImagePayload.java
│   ├── SharedWindowInteractionPayload.java
│   └── SharedWindowPermissionPayload.java
└── render/
    └── SharedWindowDisplay.java
```

## Git流程
- 每个阶段完成后 git add + commit
- 不执行 git push
- commit message 格式：`feat(shared): 实现xxx功能`
