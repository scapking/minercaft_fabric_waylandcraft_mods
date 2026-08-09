package dev.evvie.waylandcraft.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WindowDisplay;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.capture.PipeWireCaptureManager;
import dev.evvie.waylandcraft.desktop.DesktopEntry;
import dev.evvie.waylandcraft.grabs.WindowGrab;
import dev.evvie.waylandcraft.gui.WindowManagerScreen;
import dev.evvie.waylandcraft.network.PermissionCommandPayload;
import dev.evvie.waylandcraft.network.SharedWindowClientHandler;
import dev.evvie.waylandcraft.settings.WaylandCraftSettings;
import dev.evvie.waylandcraft.shared.ImageCapture;
import dev.evvie.waylandcraft.shared.WindowPermission;
import dev.evvie.waylandcraft.shared.WindowShareManager;
import dev.evvie.waylandcraft.utils.X11WindowLister;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

public class WaylandCraftCommand {

	private static final String SHORT_PREFIX = "0x";

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register(WaylandCraftCommand::registerCommands);
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
		dispatcher.register(
			ClientCommands.literal("wl")
				.executes(WaylandCraftCommand::showHelp)
				.then(ClientCommands.literal("help")
					.executes(WaylandCraftCommand::showHelp)
				)
				.then(ClientCommands.literal("list")
					.then(ClientCommands.literal("windows")
						.executes(WaylandCraftCommand::listWindows)
					)
					.then(ClientCommands.literal("apps")
						.executes(WaylandCraftCommand::listApps)
					)
					.then(ClientCommands.literal("desktop")
						.executes(WaylandCraftCommand::listDesktopWindows)
					)
				)
				.then(ClientCommands.literal("launch")
					.then(ClientCommands.argument("app_name", StringArgumentType.greedyString())
						.executes(WaylandCraftCommand::launchWindow)
					)
				)
				.then(ClientCommands.literal("give")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::giveWindowItem)
					)
				)
				.then(ClientCommands.literal("take")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::takeWindowItem)
					)
				)
				.then(ClientCommands.literal("capture")
					.executes(WaylandCraftCommand::captureWindow)
				)
				.then(ClientCommands.literal("grab")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::grabWindow)
					)
				)
				.then(ClientCommands.literal("show")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::showWindow)
					)
				)
				.then(ClientCommands.literal("hide")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::hideWindow)
					)
				)
				.then(ClientCommands.literal("pin")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::pinWindow)
					)
				)
				.then(ClientCommands.literal("unpin")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::unpinWindow)
					)
				)
				.then(ClientCommands.literal("close")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.executes(WaylandCraftCommand::closeWindow)
					)
				)
				.then(ClientCommands.literal("resize")
					.then(ClientCommands.argument("handle", StringArgumentType.word())
						.then(ClientCommands.argument("width", IntegerArgumentType.integer(1, 10000))
							.then(ClientCommands.argument("height", IntegerArgumentType.integer(1, 10000))
								.executes(WaylandCraftCommand::resizeWindow)
							)
						)
					)
				)
				.then(ClientCommands.literal("settings")
					.then(ClientCommands.literal("list")
						.executes(WaylandCraftCommand::listSettings)
					)
					.then(ClientCommands.literal("set")
						.then(ClientCommands.argument("key", StringArgumentType.word())
							.then(ClientCommands.argument("value", StringArgumentType.word())
								.executes(WaylandCraftCommand::setSetting)
							)
						)
					)
				)
				.then(ClientCommands.literal("share")
					.then(ClientCommands.literal("start")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.executes(WaylandCraftCommand::shareWindow)
						)
					)
					.then(ClientCommands.literal("stop")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.executes(WaylandCraftCommand::unshareWindow)
						)
					)
					.then(ClientCommands.literal("quality")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.then(ClientCommands.argument("scale", FloatArgumentType.floatArg(0.1f, 1.0f))
								.then(ClientCommands.argument("quality", FloatArgumentType.floatArg(0.1f, 1.0f))
									.then(ClientCommands.argument("fps", IntegerArgumentType.integer(5, 120))
										.executes(WaylandCraftCommand::setShareQuality)
									)
								)
							)
						)
					)
					.then(ClientCommands.literal("preset")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.then(ClientCommands.argument("preset", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (String p : new String[]{"performance", "quality", "balanced", "lowlatency"})
										builder.suggest(p);
									return builder.buildFuture();
								})
								.executes(WaylandCraftCommand::applySharePreset)
							)
						)
					)
					.then(ClientCommands.literal("config")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.then(ClientCommands.argument("param", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (String p : new String[]{"scale", "quality", "fps", "diff", "bitrate", "buffer", "latency", "prediction", "compression", "diffThreshold"})
										builder.suggest(p);
									return builder.buildFuture();
								})
								.then(ClientCommands.argument("value", StringArgumentType.word())
									.executes(WaylandCraftCommand::setShareConfig)
								)
							)
						)
					)
					.then(ClientCommands.literal("reset")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.executes(WaylandCraftCommand::resetShareQuality)
						)
					)
					.then(ClientCommands.literal("info")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.executes(WaylandCraftCommand::showShareConfig)
						)
					)
					.then(ClientCommands.literal("resolution")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.then(ClientCommands.argument("width", IntegerArgumentType.integer(1, 3840))
								.then(ClientCommands.argument("height", IntegerArgumentType.integer(1, 2160))
									.executes(WaylandCraftCommand::setShareResolution)
								)
							)
						)
					)
					.then(ClientCommands.literal("stats")
						.then(ClientCommands.argument("handle", StringArgumentType.word())
							.executes(WaylandCraftCommand::showShareStats)
						)
					)
				)
				// 权限管理 - 任意玩家可用
				.then(ClientCommands.literal("permission")
					.then(ClientCommands.literal("list")
						.executes(WaylandCraftCommand::permList)
					)
					.then(ClientCommands.literal("default")
						.then(ClientCommands.argument("permission", StringArgumentType.word())
							.suggests((ctx, builder) -> {
								for (WindowPermission p : WindowPermission.values()) builder.suggest(p.name());
								return builder.buildFuture();
							})
							.executes(WaylandCraftCommand::permDefault)
						)
					)
					.then(ClientCommands.literal("allow")
						.then(ClientCommands.argument("player", StringArgumentType.word())
							.then(ClientCommands.argument("permission", StringArgumentType.word())
								.suggests((ctx, builder) -> {
									for (WindowPermission p : WindowPermission.values()) builder.suggest(p.name());
									return builder.buildFuture();
								})
								.executes(WaylandCraftCommand::permAllow)
							)
						)
					)
					.then(ClientCommands.literal("deny")
						.then(ClientCommands.argument("player", StringArgumentType.word())
							.executes(WaylandCraftCommand::permDeny)
						)
					)
					.then(ClientCommands.literal("remove")
						.then(ClientCommands.argument("player", StringArgumentType.word())
							.executes(WaylandCraftCommand::permRemove)
						)
					)
				)
		);
	}

	// ===== 帮助 =====

	/**
	 * 命令帮助 — 每个命令的语义说明，保证无歧义
	 * /wl help
	 */
	private static int showHelp(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 命令帮助§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §e/wl list windows§7  — 列出合成器窗口§r"));
		source.sendFeedback(Component.literal(" §e/wl list apps§7     — 列出可启动应用§r"));
		source.sendFeedback(Component.literal(" §e/wl list desktop§7  — 列出可捕获的桌面窗口§r"));
		source.sendFeedback(Component.literal(" §e/wl launch <app>§7  — 启动应用§r"));
		source.sendFeedback(Component.literal(" §e/wl give <handle>§7 — 把窗口变为物品放入背包§r"));
		source.sendFeedback(Component.literal(" §e/wl take <handle>§7 — 从背包收回窗口物品§r"));
		source.sendFeedback(Component.literal(" §e/wl capture§7      — 弹出Portal选择，捕获桌面窗口§r"));
		source.sendFeedback(Component.literal(" §e/wl grab <handle>§7 — 抓取窗口，移动鼠标在世界中拖动§r"));
		source.sendFeedback(Component.literal(" §e/wl show <handle>§7 — 在世界中显示窗口§r"));
		source.sendFeedback(Component.literal(" §e/wl hide <handle>§7 — 从世界中隐藏窗口显示§r"));
		source.sendFeedback(Component.literal(" §e/wl pin <handle>§7  — 钉住窗口（世界中保持显示，不受隐藏/最小化影响）§r"));
		source.sendFeedback(Component.literal(" §e/wl unpin <handle>§7— 解除钉住§r"));
		source.sendFeedback(Component.literal(" §e/wl close <handle>§7— 终止应用进程（关闭窗口）§r"));
		source.sendFeedback(Component.literal(" §e/wl resize <handle> <w> <h>§7 — 调整窗口分辨率§r"));
		source.sendFeedback(Component.literal(" §e/wl settings list|set <key> <value>§7 — 查看/修改设置§r"));
		source.sendFeedback(Component.literal(" §e/wl share start|stop|quality|preset|config|reset|info|resolution|stats <handle> [...]§7 — 共享管理§r"));
		source.sendFeedback(Component.literal(" §e/wl permission list|default|allow|deny|remove§7 — 共享权限管理§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §7<handle> 支持 0x短句柄 / 完整句柄 / 窗口别名（如 firefox_esr）§r"));
		return 1;
	}

	// ===== Handle & Alias =====

	private static String shortHex(long handle) {
		return SHORT_PREFIX + Long.toHexString(handle & 0xFFFF);
	}

	/**
	 * 生成窗口别名：小写+下划线，去除空格和特殊字符
	 * "Firefox ESR" → "firefox_esr"
	 * "Google Chrome" → "google_chrome"
	 */
	private static String getWindowAlias(WLCToplevel toplevel) {
		String name = getWindowDisplayName(toplevel);
		return name.toLowerCase()
			.replaceAll("[^a-z0-9\\s]", "") // 移除特殊字符
			.trim()
			.replaceAll("\\s+", "_"); // 空格→下划线
	}

	private static long parseWindowHandle(String handleStr) {
		handleStr = handleStr.trim();
		try {
			if(handleStr.toLowerCase().startsWith("0x")) {
				return Long.parseLong(handleStr.substring(2), 16);
			}
			return Long.parseLong(handleStr);
		} catch(NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * 查找窗口 - 支持 hex handle、别名、后缀匹配
	 * 别名支持序号：别名:N 表示第 N 个同别名窗口（1 起），
	 * 解决多个同名窗口（如多个 firefox）只能操作第一个的问题。
	 */
	private static WLCToplevel findToplevelByHandle(FabricClientCommandSource source, String handleStr) {
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return null;
		}

		WLCToplevel[] toplevels = wlc.bridge.getToplevels();

		// 1. 尝试 hex handle 解析
		long handle = parseWindowHandle(handleStr);
		if(handle >= 0) {
			WLCToplevel t = wlc.bridge.getToplevel(handle);
			if(t != null) return t;
		}

		// 1.5 别名+序号：alias:N（如 firefox:2）
		int colonIdx = handleStr.lastIndexOf(':');
		if(colonIdx > 0) {
			String numPart = handleStr.substring(colonIdx + 1);
			String aliasPart = handleStr.substring(0, colonIdx).toLowerCase().replaceAll("[^a-z0-9_]", "");
			try {
				int n = Integer.parseInt(numPart);
				if(n >= 1) {
					int count = 0;
					for(WLCToplevel t : toplevels) {
						if(getWindowAlias(t).equals(aliasPart)) {
							count++;
							if(count == n) return t;
						}
					}
					if(count > 0) {
						source.sendError(Component.literal("§c✘ Window alias §e" + aliasPart + "§c has only " + count + " match(es), requested #" + n + "§r"));
						return null;
					}
				}
			} catch(NumberFormatException ignored) {
				// 不是序号语法，继续走别名匹配
			}
		}

		// 2. 后缀匹配（支持短handle如 0xABCD）
		String hex = handleStr.toLowerCase().replace("0x", "");
		for(WLCToplevel t : toplevels) {
			String fullHex = Long.toHexString(t.getHandle());
			if(fullHex.endsWith(hex)) {
				return t;
			}
		}

		// 3. 别名匹配（精确）
		String aliasInput = handleStr.toLowerCase().replaceAll("[^a-z0-9_]", "");
		for(WLCToplevel t : toplevels) {
			String alias = getWindowAlias(t);
			if(alias.equals(aliasInput)) {
				return t;
			}
		}

		// 4. 别名模糊匹配（包含）
		for(WLCToplevel t : toplevels) {
			String alias = getWindowAlias(t);
			if(alias.contains(aliasInput) || aliasInput.contains(alias)) {
				return t;
			}
		}

		source.sendError(Component.literal("§c✘ Window not found: " + handleStr + "§r"));
		return null;
	}

	private static String getWindowDisplayName(WLCToplevel toplevel) {
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.xdgManager == null) {
			return toplevel.title != null ? toplevel.title : "Unknown";
		}

		DesktopEntry entry = wlc.xdgManager.forAppId(toplevel.appID);
		if(entry != null && entry.name != null) {
			return entry.name;
		}

		return toplevel.title != null ? toplevel.title : "Unknown";
	}

	private static boolean isWindowShared(long handle) {
		return SharedWindowClientHandler.getRemoteWindow(handle) != null;
	}

	// ===== 窗口命令 =====

	private static int listWindows(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;

		if(wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		WLCToplevel[] toplevels = wlc.bridge.getToplevels();
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 Windows §7(" + toplevels.length + " total)§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));

		if(toplevels.length == 0) {
			source.sendFeedback(Component.literal(" §7No windows detected§r"));
		} else {
			// 统计别名出现次数，给同名窗口加序号（firefox:1, firefox:2 …）
			java.util.Map<String, Integer> aliasCounts = new java.util.HashMap<>();
			for(WLCToplevel toplevel : toplevels) {
				aliasCounts.merge(getWindowAlias(toplevel), 1, Integer::sum);
			}
			java.util.Map<String, Integer> aliasSeen = new java.util.HashMap<>();

			for(WLCToplevel toplevel : toplevels) {
				String hex = shortHex(toplevel.getHandle());
				String alias = getWindowAlias(toplevel);
				String displayName = getWindowDisplayName(toplevel);
				int w = toplevel.geometry.width();
				int h = toplevel.geometry.height();
				boolean shared = isWindowShared(toplevel.getHandle());

				int n = aliasSeen.merge(alias, 1, Integer::sum);
				String line = " §e" + hex + "§r §a" + alias + "§r";
				if(aliasCounts.getOrDefault(alias, 0) > 1) {
					line += " §7#" + n + "§r";
				}
				line += " §f" + displayName + "§r §7" + w + "x" + h + "§r";
				if(shared) line += " §a✔§r";

				source.sendFeedback(Component.literal(line));
			}
		}

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §7Use §e/wl resize <handle> <w> <h>§7 to resize§r"));
		source.sendFeedback(Component.literal(" §7Use §e/wl show <handle>§7 to show in world, §e/wl share start <handle>§7 to share§r"));
		return toplevels.length;
	}

	private static int listApps(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;

		if(wlc == null || wlc.xdgManager == null) {
			source.sendError(Component.literal("§c✘ Desktop entries not loaded§r"));
			return 0;
		}

		List<DesktopEntry> entries = wlc.xdgManager.entries();
		List<DesktopEntry> visible = new ArrayList<>();
		for(DesktopEntry e : entries) {
			if(e.visible && e.name != null) visible.add(e);
		}

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 Apps §7(" + visible.size() + " total)§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));

		for(DesktopEntry entry : visible) {
			String name = entry.name;
			String desc = entry.genericName != null ? entry.genericName : "";
			String line = " §b" + name + "§r";
			if(!desc.isEmpty()) line += " §7- §8" + desc + "§r";
			source.sendFeedback(Component.literal(line));
		}

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §7Use §e/wl launch <name>§7 to launch§r"));
		return 1;
	}

	/**
	 * 启动应用（纯启动语义：只负责从桌面条目启动，不给物品）
	 * /wl launch <app>
	 */
	private static int launchWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String appName = StringArgumentType.getString(context, "app_name").trim();
		WaylandCraft wlc = WaylandCraft.instance;

		if(wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		if(wlc.xdgManager == null) {
			source.sendError(Component.literal("§c✘ Desktop entries not loaded§r"));
			return 0;
		}

		List<DesktopEntry> entries = wlc.xdgManager.entries();
		List<DesktopEntry> matches = new ArrayList<>();

		for(DesktopEntry entry : entries) {
			if(entry.name != null && entry.name.toLowerCase().contains(appName.toLowerCase())) {
				matches.add(entry);
			} else if(entry.genericName != null && entry.genericName.toLowerCase().contains(appName.toLowerCase())) {
				matches.add(entry);
			} else if(entry.appId.toLowerCase().contains(appName.toLowerCase())) {
				matches.add(entry);
			}
		}

		if(matches.isEmpty()) {
			source.sendError(Component.literal("§c✘ No application found: " + appName + "§r"));
			return 0;
		}

		if(matches.size() > 1) {
			source.sendFeedback(Component.literal("§eMultiple matches:§r"));
			for(DesktopEntry entry : matches) {
				source.sendFeedback(Component.literal("  §b- " + (entry.name != null ? entry.name : entry.appId) + "§r"));
			}
			return 0;
		}

		DesktopEntry entry = matches.get(0);
		boolean launched = launchApp(wlc, entry);
		if(launched) {
			source.sendFeedback(Component.literal("§a✔ Launched: §f" + (entry.name != null ? entry.name : entry.appId) + "§r"));
			source.sendFeedback(Component.literal(" §7窗口出现后将自动获得对应物品，右键长按放置到世界中§r"));
		} else {
			source.sendError(Component.literal("§c✘ Failed to launch: " + entry.appId + "§r"));
		}
		return launched ? 1 : 0;
	}

	/**
	 * 启动应用 - 使用原生execApp（Rust层正确设置WAYLAND_DISPLAY）
	 */
	private static boolean launchApp(WaylandCraft wlc, DesktopEntry entry) {
		return wlc.bridge.execApp(entry.appId);
	}

	/**
	 * 把指定窗口变为物品放入背包（原 WM 屏 Give Item 按钮）
	 * /wl give <handle>
	 */
	private static int giveWindowItem(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.itemManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		wlc.itemManager.giveItem(toplevel);
		source.sendFeedback(Component.literal("§a✔ Gave window item: §f" + getWindowDisplayName(toplevel) + "§r"));
		return 1;
	}

	/**
	 * 抓取窗口（原 WM 屏 Grab 按钮）：进入抓取模式，移动鼠标即可在世界中拖动窗口
	 * /wl grab <handle>
	 */
	private static int grabWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.bridge == null || wlc.pointerGrabs == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		WindowDisplay display = wlc.getOrCreateDisplay(toplevel);
		wlc.pointerGrabs.startExclusive(new WindowGrab(display, 0));

		// 若当前在窗口管理屏中，退出屏幕进入世界抓取模式
		Minecraft mc = Minecraft.getInstance();
		if(mc.screen instanceof WindowManagerScreen) {
			mc.screen.onClose();
		}

		source.sendFeedback(Component.literal("§a✔ Grabbed: §f" + getWindowDisplayName(toplevel) + "§r"));
		source.sendFeedback(Component.literal(" §7移动鼠标拖动窗口，滚轮调整距离§r"));
		return 1;
	}

	/**
	 * 在世界中显示窗口（若未显示则创建显示并锚定到玩家面前）
	 * /wl show <handle>
	 */
	private static int showWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		boolean alreadyShown = wlc.hasDisplayFor(toplevel);
		WindowDisplay display = wlc.getOrCreateDisplay(toplevel);

		// 新显示时锚定到玩家面前，避免出现在世界原点
		if(!alreadyShown) {
			Minecraft mc = Minecraft.getInstance();
			Camera camera = mc.gameRenderer.getMainCamera();
			display.anchorToCamera(camera);
		}

		source.sendFeedback(Component.literal("§a✔ Shown in world: §f" + getWindowDisplayName(toplevel) + "§r"));
		if(!alreadyShown) {
			source.sendFeedback(Component.literal(" §7使用 §e/wl grab <handle>§7 调整位置§r"));
		}
		return 1;
	}

	/**
	 * 从世界中隐藏窗口显示（窗口仍在合成器中，窗口管理屏仍可见）
	 * 若窗口已钉住，先解除钉住再隐藏
	 * /wl hide <handle>
	 */
	private static int hideWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		boolean wasPinned = wlc.pinnedToplevel == toplevel;
		if(wasPinned) {
			wlc.pinnedToplevel = null;
		}

		boolean removed = wlc.displays.removeIf((w) -> w.window == toplevel);
		if(removed) {
			String pinNote = wasPinned ? "（已自动解除钉住）" : "";
			source.sendFeedback(Component.literal("§a✔ Hidden: §f" + getWindowDisplayName(toplevel) + "§r" + pinNote + " (窗口管理屏中仍可见)"));
		} else {
			if(wasPinned) {
				source.sendFeedback(Component.literal("§7" + getWindowDisplayName(toplevel) + " §7已解除钉住，但原本未在世界中显示§r"));
			} else {
				source.sendFeedback(Component.literal("§7" + getWindowDisplayName(toplevel) + " §7当前未在世界中显示§r"));
			}
		}
		return 1;
	}

	/**
	 * 钉住窗口：在世界中显示并保持显示（不受 hide/minimize 影响）
	 * /wl pin <handle>
	 */
	private static int pinWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		boolean alreadyPinned = wlc.pinnedToplevel == toplevel;
		if(!alreadyPinned) {
			// 未显示则先显示并锚定到玩家面前
			boolean alreadyShown = wlc.hasDisplayFor(toplevel);
			WindowDisplay display = wlc.getOrCreateDisplay(toplevel);
			if(!alreadyShown) {
				Minecraft mc = Minecraft.getInstance();
				Camera camera = mc.gameRenderer.getMainCamera();
				display.anchorToCamera(camera);
			}
			wlc.pinnedToplevel = toplevel;
		}

		source.sendFeedback(Component.literal("§a✔ Pinned: §f" + getWindowDisplayName(toplevel) + "§r §7（世界中保持显示，不受隐藏/最小化影响）§r"));
		return 1;
	}

	/**
	 * 解除钉住窗口
	 * /wl unpin <handle>
	 */
	private static int unpinWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		if(wlc.pinnedToplevel == toplevel) {
			wlc.pinnedToplevel = null;
			source.sendFeedback(Component.literal("§a✔ Unpinned: §f" + getWindowDisplayName(toplevel) + "§r"));
		} else {
			source.sendFeedback(Component.literal("§7" + getWindowDisplayName(toplevel) + " §7当前未处于钉住状态§r"));
		}
		return 1;
	}

	/**
	 * 列出全部设置（替代设置屏）
	 * /wl settings list
	 */
	private static int listSettings(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;

		if(wlc == null || wlc.settingsManager == null) {
			source.sendError(Component.literal("§c✘ Settings not initialized§r"));
			return 0;
		}

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 Settings§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §epixelsPerBlock§7 = §e" + wlc.settingsManager.getIntSetting(WaylandCraftSettings.PIXELS_PER_BLOCK) + "§r  §8(int, 窗口世界显示像素密度)§r"));
		source.sendFeedback(Component.literal(" §ewindowAntialiasing§7 = §e" + wlc.settingsManager.getBooleanSetting(WaylandCraftSettings.WINDOW_ANTIALIASING) + "§r  §8(bool)§r"));
		source.sendFeedback(Component.literal(" §efocusOnHover§7 = §e" + wlc.settingsManager.getBooleanSetting(WaylandCraftSettings.FOCUS_ON_HOVER) + "§r  §8(bool)§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §7Use §e/wl settings set <key> <value>§7 to set§r"));
		return 1;
	}

	/**
	 * 设置单个设置项（替代设置屏）
	 * /wl settings set <key> <value>
	 */
	private static int setSetting(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String key = StringArgumentType.getString(context, "key");
		String value = StringArgumentType.getString(context, "value");

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.settingsManager == null) {
			source.sendError(Component.literal("§c✘ Settings not initialized§r"));
			return 0;
		}

		try {
			switch(key) {
				case "pixelsPerBlock" -> {
					wlc.settingsManager.setIntSetting(WaylandCraftSettings.PIXELS_PER_BLOCK, Integer.parseInt(value));
					source.sendFeedback(Component.literal("§a✔ §epixelsPerBlock§r = §e" + value + "§r"));
					return 1;
				}
				case "windowAntialiasing" -> {
					wlc.settingsManager.setBooleanSetting(WaylandCraftSettings.WINDOW_ANTIALIASING, Boolean.parseBoolean(value));
					source.sendFeedback(Component.literal("§a✔ §ewindowAntialiasing§r = §e" + value + "§r"));
					return 1;
				}
				case "focusOnHover" -> {
					wlc.settingsManager.setBooleanSetting(WaylandCraftSettings.FOCUS_ON_HOVER, Boolean.parseBoolean(value));
					source.sendFeedback(Component.literal("§a✔ §efocusOnHover§r = §e" + value + "§r"));
					return 1;
				}
				default -> {
					source.sendError(Component.literal("§c✘ Unknown setting: §f" + key + "§r"));
					source.sendFeedback(Component.literal(" §7Available: pixelsPerBlock, windowAntialiasing, focusOnHover§r"));
					return 0;
				}
			}
		} catch(NumberFormatException e) {
			source.sendError(Component.literal("§c✘ Invalid value: §f" + value + "§r"));
			return 0;
		}
	}

	// ===== 桌面窗口捕获 =====

	/**
	 * 列出可捕获的桌面窗口（通过 JNA X11）
	 */
	private static int listDesktopWindows(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;

		try {
			// 通过本地库获取桌面窗口（自动检测 wlr/GNOME//proc）
			List<X11WindowLister.WindowInfo> windowInfos = X11WindowLister.getDesktopWindows();

			source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
			source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 Desktop Windows §7(" + windowInfos.size() + " total)§r"));
			source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));

			if (windowInfos.isEmpty()) {
				source.sendFeedback(Component.literal(" §7No desktop windows detected§r"));
			} else {
			for (X11WindowLister.WindowInfo info : windowInfos) {
				String desc = !info.appId.isEmpty() && !info.appId.equals(info.title) ? " §7- §8" + info.appId + "§r" : "";
				source.sendFeedback(Component.literal(" §a[" + info.hash + "]§r §b" + info.title + "§r" + desc + " §7pid:" + info.pid + "§r"));
			}
			}

			source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
			source.sendFeedback(Component.literal(" §7Use §e/wl capture§7 to start capture§r"));
			return windowInfos.size();
		} catch (Exception e) {
			source.sendError(Component.literal("§c✘ Failed to list desktop windows: " + e.getMessage() + "§r"));
			return 0;
		}
	}

	/**
	 * 捕获桌面窗口（通过 XDG Desktop Portal ScreenCast）
	 * 会弹出窗口选择对话框，用户选择后自动开始捕获
	 */
	private static int captureWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;

		if (wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		source.sendFeedback(Component.literal("§e⏳ 正在启动 Portal 捕获...请在弹窗中选择要共享的窗口§r"));

		try {
			// 启动 Portal ScreenCast 捕获（会弹出确认对话框）
			PipeWireCaptureManager.CaptureSession session = wlc.captureManager.startCapture();

			if (session == null) {
				source.sendError(Component.literal("§c✘ Portal 捕获失败（可能被取消或超时）§r"));
				return 0;
			}

			// 注册虚拟 Toplevel 用于渲染
			session.registerToplevel("Portal Capture");

			source.sendFeedback(Component.literal("§a✔ Portal 捕获已启动§r"));
			source.sendFeedback(Component.literal(" §7窗口将在游戏世界中显示§r"));
			return 1;

		} catch (Exception e) {
			source.sendError(Component.literal("§c✘ 捕获失败: " + e.getMessage() + "§r"));
			return 0;
		}
	}

	/**
	 * 从背包收回窗口物品（give 的逆操作）
	 * /wl take <handle>
	 */
	private static int takeWindowItem(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		long handle = parseWindowHandle(handleStr);

		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null) {
			source.sendError(Component.literal("§c✘ No player available§r"));
			return 0;
		}

		var inventory = mc.player.getInventory();
		boolean found = false;
		for(int i = 0; i < inventory.getContainerSize(); i++) {
			var stack = inventory.getItem(i);
			Long itemHandle = stack.get(dev.evvie.waylandcraft.item.WindowItem.WINDOW_HANDLE);
			if(itemHandle != null) {
				if(itemHandle == handle || Long.toHexString(itemHandle).endsWith(handleStr.toLowerCase().replace("0x", ""))) {
					inventory.removeItem(i, 1);
					found = true;
					break;
				}
			}
		}

		if(found) {
			source.sendFeedback(Component.literal("§a✔ Took back window item §e" + handleStr + "§r"));
		} else {
			source.sendError(Component.literal("§c✘ No window item in inventory: " + handleStr + "§r"));
		}
		return found ? 1 : 0;
	}

	private static int closeWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		String displayName = getWindowDisplayName(toplevel);
		if(toplevel.appID != null && !toplevel.appID.isEmpty()) {
			try {
				ProcessBuilder pb = new ProcessBuilder("pkill", "-f", toplevel.appID);
				pb.start();
				source.sendFeedback(Component.literal("§a✔ Closed: §f" + displayName + "§r"));
				return 1;
			} catch(Exception e) {
				source.sendError(Component.literal("§c✘ Failed: " + e.getMessage() + "§r"));
				return 0;
			}
		}
		source.sendError(Component.literal("§c✘ No app ID available§r"));
		return 0;
	}

	private static int resizeWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");
		int width = IntegerArgumentType.getInteger(context, "width");
		int height = IntegerArgumentType.getInteger(context, "height");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.bridge == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		wlc.bridge.resizeToplevelInteractive(toplevel, width, height);
		String alias = getWindowAlias(toplevel);
		source.sendFeedback(Component.literal("§a✔ Resized §f" + alias + "§r → §e" + width + "x" + height + "§r"));
		return 1;
	}

	private static int shareWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		String displayName = getWindowDisplayName(toplevel);
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc != null && wlc.windowShareManager != null) {
			wlc.windowShareManager.startSharing(toplevel.getHandle(), displayName);
		} else {
			SharedWindowClientHandler.requestWindowRegister(toplevel.getHandle(), displayName);
		}
		source.sendFeedback(Component.literal("§a✔ Shared: §f" + displayName + "§r §e" + shortHex(toplevel.getHandle()) + "§r"));
		return 1;
	}

	private static int unshareWindow(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String handleStr = StringArgumentType.getString(context, "handle");

		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc != null && wlc.windowShareManager != null) {
			wlc.windowShareManager.stopSharing(toplevel.getHandle());
		} else {
			SharedWindowClientHandler.requestWindowUnregister(toplevel.getHandle());
		}
		source.sendFeedback(Component.literal("§a✔ Unshared: §f" + getWindowDisplayName(toplevel) + "§r"));
		return 1;
	}

	private static int setShareQuality(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		long handle = toplevel.getHandle();
		float scale = FloatArgumentType.getFloat(context, "scale");
		float quality = FloatArgumentType.getFloat(context, "quality");
		int fps = IntegerArgumentType.getInteger(context, "fps");

		ImageCapture.CaptureConfig config = new ImageCapture.CaptureConfig(scale, quality, fps);
		wlc.windowShareManager.setPerWindowConfig(handle, config);

		source.sendFeedback(Component.literal("§a✔ Quality set for §f" + getWindowDisplayName(toplevel) + "§r"));
		source.sendFeedback(Component.literal(" §7Scale: §e" + scale + "§7 Quality: §e" + quality + "§7 FPS: §e" + fps + "§r"));
		return 1;
	}

	private static int resetShareQuality(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		long handle = toplevel.getHandle();
		wlc.windowShareManager.clearPerWindowConfig(handle);

		source.sendFeedback(Component.literal("§a✔ Share config reset for §f" + getWindowDisplayName(toplevel) + "§r (using global config)"));
		return 1;
	}

	private static int setShareConfig(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		String param = StringArgumentType.getString(context, "param").toLowerCase();
		String value = StringArgumentType.getString(context, "value").toLowerCase();

		// 获取当前配置（如果没有则创建默认）
		WindowShareManager.ShareState state = wlc.windowShareManager.getShareState(toplevel.getHandle());
		ImageCapture.CaptureConfig config = state != null && state.perWindowConfig != null 
			? state.perWindowConfig 
			: ImageCapture.CaptureConfig.balanced();

		try {
			switch(param) {
				case "scale" -> config.scale = Float.parseFloat(value);
				case "quality" -> config.quality = Float.parseFloat(value);
				case "fps" -> config.maxFps = Integer.parseInt(value);
				case "diff" -> config.diffUpdate = Boolean.parseBoolean(value);
				case "bitrate" -> config.maxBitrate = Integer.parseInt(value);
				case "buffer" -> config.frameBuffer = Integer.parseInt(value);
				case "latency" -> config.latencyComp = Integer.parseInt(value);
				case "prediction" -> config.prediction = Boolean.parseBoolean(value);
				case "compression" -> config.compression = value;
				case "diffThreshold" -> config.diffThreshold = Float.parseFloat(value);
				default -> {
					source.sendError(Component.literal("§c✘ Unknown parameter: §f" + param + "§r"));
					source.sendFeedback(Component.literal(" §7Available: scale, quality, fps, diff, bitrate, buffer, latency, prediction, compression, diffThreshold§r"));
					return 0;
				}
			}
			wlc.windowShareManager.setPerWindowConfig(toplevel.getHandle(), config);
			source.sendFeedback(Component.literal("§a✔ §f" + param + "§r = §e" + value + "§r for §f" + getWindowDisplayName(toplevel) + "§r"));
		} catch(NumberFormatException e) {
			source.sendError(Component.literal("§c✘ Invalid value: §f" + value + "§r"));
			return 0;
		}
		return 1;
	}

	private static int applySharePreset(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		String preset = StringArgumentType.getString(context, "preset").toLowerCase();
		ImageCapture.CaptureConfig config;

		switch(preset) {
			case "performance" -> config = ImageCapture.CaptureConfig.highPerformance();
			case "quality" -> config = ImageCapture.CaptureConfig.highQuality();
			case "balanced" -> config = ImageCapture.CaptureConfig.balanced();
			case "lowlatency" -> config = ImageCapture.CaptureConfig.lowLatency();
			default -> {
				source.sendError(Component.literal("§c✘ Unknown preset: §f" + preset + "§r"));
				source.sendFeedback(Component.literal(" §7Available: performance, quality, balanced, lowlatency§r"));
				return 0;
			}
		}

		wlc.windowShareManager.setPerWindowConfig(toplevel.getHandle(), config);
		source.sendFeedback(Component.literal("§a✔ Applied preset §e" + preset + "§r to §f" + getWindowDisplayName(toplevel) + "§r"));
		source.sendFeedback(Component.literal(" §7" + config.getSummary() + "§r"));
		return 1;
	}

	private static int showShareConfig(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WindowShareManager.ShareState state = wlc.windowShareManager.getShareState(toplevel.getHandle());
		ImageCapture.CaptureConfig config = state != null && state.perWindowConfig != null 
			? state.perWindowConfig 
			: null;

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 Share Config: §f" + getWindowDisplayName(toplevel) + "§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));

		if(config != null) {
			source.sendFeedback(Component.literal(" §7Scale: §e" + config.scale + "§r"));
			source.sendFeedback(Component.literal(" §7Quality: §e" + config.quality + "§r"));
			source.sendFeedback(Component.literal(" §7FPS: §e" + config.maxFps + "§r"));
			source.sendFeedback(Component.literal(" §7Diff Update: §e" + config.diffUpdate + "§r"));
			source.sendFeedback(Component.literal(" §7Bitrate: §e" + (config.maxBitrate > 0 ? config.maxBitrate + "kbps" : "unlimited") + "§r"));
			source.sendFeedback(Component.literal(" §7Buffer: §e" + config.frameBuffer + " frames§r"));
			source.sendFeedback(Component.literal(" §7Latency Comp: §e" + config.latencyComp + "ms§r"));
			source.sendFeedback(Component.literal(" §7Prediction: §e" + config.prediction + "§r"));
			source.sendFeedback(Component.literal(" §7Compression: §e" + config.compression + "§r"));
			source.sendFeedback(Component.literal(" §7Diff Threshold: §e" + String.format("%.3f", config.diffThreshold) + "§r"));
		} else {
			source.sendFeedback(Component.literal(" §7Using global config§r"));
		}

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §7Presets: §eperformance§7, §equality§7, §ebalanced§7, §elowlatency§r"));
		source.sendFeedback(Component.literal(" §7Use §e/wl share config <handle> <param> <value>§7 to set§r"));
		source.sendFeedback(Component.literal(" §7Use §e/wl share reset <handle>§7 to restore global defaults§r"));
		return 1;
	}

	/**
	 * 设置共享窗口的捕获目标分辨率
	 * /wl share resolution <handle> <width> <height>
	 */
	private static int setShareResolution(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		int targetW = IntegerArgumentType.getInteger(context, "width");
		int targetH = IntegerArgumentType.getInteger(context, "height");

		int srcW = toplevel.geometry.width();
		int srcH = toplevel.geometry.height();
		if(srcW <= 0 || srcH <= 0) {
			source.sendError(Component.literal("§c✘ Window has no geometry§r"));
			return 0;
		}

		float scale = Math.min(1.0f, Math.min((float)targetW / srcW, (float)targetH / srcH));
		scale = Math.max(0.1f, scale);

		ImageCapture.CaptureConfig config = new ImageCapture.CaptureConfig(scale, 0.7f, 20);
		wlc.windowShareManager.setPerWindowConfig(toplevel.getHandle(), config);

		int actualW = (int)(srcW * scale);
		int actualH = (int)(srcH * scale);

		source.sendFeedback(Component.literal("§a✔ Resolution set to §e" + actualW + "x" + actualH + "§a (scale=" + String.format("%.2f", scale) + ")§r"));
		return 1;
	}

	/**
	 * 显示共享窗口统计信息
	 * /wl share stats <handle>
	 */
	private static int showShareStats(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		WaylandCraft wlc = WaylandCraft.instance;
		if(wlc == null || wlc.windowShareManager == null) {
			source.sendError(Component.literal("§c✘ WaylandCraft not initialized§r"));
			return 0;
		}

		String handleStr = StringArgumentType.getString(context, "handle");
		WLCToplevel toplevel = findToplevelByHandle(source, handleStr);
		if(toplevel == null) return 0;

		WindowShareManager.ShareState state = wlc.windowShareManager.getShareState(toplevel.getHandle());
		if(state == null) {
			source.sendError(Component.literal("§c✘ Window §e" + handleStr + "§c is not being shared§r"));
			return 0;
		}

		long uptime = (System.currentTimeMillis() - state.startTime) / 1000;
		float avgFps = uptime > 0 ? (float)state.frameCount / uptime : 0;
		String avgSize = state.frameCount > 0 ? (state.totalBytes / state.frameCount / 1024) + "KB" : "N/A";

		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal("§6 §lWaylandCraft §r§7 Share Stats: §f" + getWindowDisplayName(toplevel) + "§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		source.sendFeedback(Component.literal(" §7Frames: §e" + state.frameCount + "§7 (skipped: §e" + state.skippedFrames + "§7, rate-limited: §e" + state.rateLimitedFrames + "§7)§r"));
		source.sendFeedback(Component.literal(" §7Total: §e" + (state.totalBytes / 1024) + "KB§7 in §e" + uptime + "s§r"));
		source.sendFeedback(Component.literal(" §7Avg Frame: §e" + avgSize + "§7, Avg FPS: §e" + String.format("%.1f", avgFps) + "§r"));
		source.sendFeedback(Component.literal(" §7Current FPS: §e" + state.currentFps + "§7, Bitrate: §e" + state.currentBitrate + "kbps§r"));
		source.sendFeedback(Component.literal(" §7Adaptive Scale: §e" + String.format("%.2f", wlc.windowShareManager.getAdaptiveScaleMultiplier()) + "§r"));
		source.sendFeedback(Component.literal(" §7Bandwidth Util: §e" + String.format("%.1f%%", wlc.windowShareManager.getBitrateUtilization() * 100) + "§r"));
		source.sendFeedback(Component.literal("§6▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
		return 1;
	}

	// ===== 权限命令 =====

	private static int permList(CommandContext<FabricClientCommandSource> context) {
		ClientPlayNetworking.send(new PermissionCommandPayload(
			PermissionCommandPayload.ACTION_LIST, "", (byte) 0));
		return 1;
	}

	private static int permDefault(CommandContext<FabricClientCommandSource> context) {
		String permStr = StringArgumentType.getString(context, "permission").toUpperCase();
		WindowPermission perm = parsePerm(permStr, context.getSource());
		if(perm == null) return 0;
		ClientPlayNetworking.send(new PermissionCommandPayload(
			PermissionCommandPayload.ACTION_SET_DEFAULT, "", (byte) perm.ordinal()));
		return 1;
	}

	private static int permAllow(CommandContext<FabricClientCommandSource> context) {
		String playerName = StringArgumentType.getString(context, "player");
		String permStr = StringArgumentType.getString(context, "permission").toUpperCase();
		WindowPermission perm = parsePerm(permStr, context.getSource());
		if(perm == null) return 0;
		ClientPlayNetworking.send(new PermissionCommandPayload(
			PermissionCommandPayload.ACTION_ALLOW, playerName, (byte) perm.ordinal()));
		return 1;
	}

	private static int permDeny(CommandContext<FabricClientCommandSource> context) {
		String playerName = StringArgumentType.getString(context, "player");
		ClientPlayNetworking.send(new PermissionCommandPayload(
			PermissionCommandPayload.ACTION_DENY, playerName, (byte) 0));
		return 1;
	}

	private static int permRemove(CommandContext<FabricClientCommandSource> context) {
		String playerName = StringArgumentType.getString(context, "player");
		ClientPlayNetworking.send(new PermissionCommandPayload(
			PermissionCommandPayload.ACTION_REMOVE, playerName, (byte) 0));
		return 1;
	}

	private static WindowPermission parsePerm(String permStr, FabricClientCommandSource source) {
		try {
			return WindowPermission.valueOf(permStr);
		} catch(IllegalArgumentException e) {
			source.sendError(Component.literal("§c✘ Invalid permission: " + permStr + " (NONE/VIEW/INTERACT/CONTROL)§r"));
			return null;
		}
	}
}
