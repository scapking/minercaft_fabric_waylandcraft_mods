package dev.evvie.waylandcraft.network;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.shared.SharedWindowEntry;
import dev.evvie.waylandcraft.shared.SharedWindowManager;
import dev.evvie.waylandcraft.shared.WindowPermission;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 共享窗口"最新帧缓存 + tick 批量转发"器。
 * 
 * 背景：发送端在 netty 线程收到图像帧，如果直接在 receiver 里同步转发给所有
 * VIEW 玩家（每次 ServerPlayNetworking.send = 一次完整 encode + 写 socket），
 * 发送者连接的网络线程会被转发工作堵死。
 * 
 * 方案：
 * - receiver（netty 线程）只做权限校验，然后把最新帧放入缓存，绝不 send。
 * - 服务端 tick 每 2 tick（约 50ms = 20fps）批量转发缓存里的所有最新帧。
 * - 缓存按 windowHandle 覆盖旧帧 = 丢中间帧；转发时逐个 remove 取出，
 *   不用 clear()，避免并发 put 的新帧被误删，保证不丢"最新帧"。
 */
public class SharedWindowFrameRelay {

	/** 按 windowHandle 缓存每个窗口的最新帧；put 覆盖旧帧 = 丢中间帧 */
	private final Map<Long, SharedWindowImagePayload> latestFrames = new ConcurrentHashMap<>();

	/** MC CustomPacketPayload 约 2MB 包大小上限保护：超过该字节数告警并跳过 */
	private static final int MAX_FRAME_BYTES = 1_900_000;

	/** 转发间隔：每 2 tick 转发一次（约 50ms = 20fps） */
	private static final int RELAY_INTERVAL_TICKS = 2;

	/** 只在服务端线程读写 */
	private int tickCounter = 0;

	/**
	 * 注册服务端 tick 转发。必须在初始化流程里与 receiver 注册一起调用。
	 */
	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
	}

	/**
	 * netty 线程调用：仅把最新帧放入缓存，这里绝不调用 ServerPlayNetworking.send。
	 * 由调用方（receiver）保证权限校验已通过。
	 */
	public void acceptFrame(SharedWindowImagePayload payload) {
		latestFrames.put(payload.windowHandle(), payload);
	}

	private void onServerTick(MinecraftServer server) {
		tickCounter++;
		if (tickCounter < RELAY_INTERVAL_TICKS) return;
		tickCounter = 0;
		relayFrames(server);
	}

	private void relayFrames(MinecraftServer server) {
		if (latestFrames.isEmpty()) return;

		SharedWindowManager manager = WaylandCraftCommon.instance.sharedWindowManager;
		var players = server.getPlayerList().getPlayers();

		// 迭代器逐个 remove 取出，不用 clear()：
		// 转发期间并发 put 的新帧不会被误删，下个批次继续转发。
		Iterator<Map.Entry<Long, SharedWindowImagePayload>> it = latestFrames.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, SharedWindowImagePayload> frameEntry = it.next();
			it.remove();
			SharedWindowImagePayload payload = frameEntry.getValue();

			// 包大小保护：超过上限告警并跳过该帧，避免协议崩
			if (payload.imageData().length > MAX_FRAME_BYTES) {
				WaylandCraftCommon.LOGGER.warn("[SERVER] image frame too large ({} bytes), skipping windowHandle={}",
					payload.imageData().length, payload.windowHandle());
				continue;
			}

			// 转发前再次确认窗口仍存在（可能已注销）
			SharedWindowEntry entry = manager.getWindow(payload.windowHandle());
			if (entry == null) {
				continue;
			}
			UUID senderUUID = entry.getOwnerUUID();

			// 批量转发给所有有 VIEW 权限的在线玩家（跳过发送者本人）
			int forwarded = 0;
			for (ServerPlayer player : players) {
				if (player.getUUID().equals(senderUUID)) continue;
				if (entry.hasPermission(player.getUUID(), WindowPermission.VIEW)) {
					ServerPlayNetworking.send(player, payload);
					forwarded++;
				}
			}
			WaylandCraftCommon.LOGGER.info("[SERVER] forwarded image from {} to {} players ({} bytes)",
				senderUUID, forwarded, payload.imageData().length);
		}
	}

}
