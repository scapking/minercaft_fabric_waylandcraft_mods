package dev.evvie.waylandcraft.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.shared.SharedWindowEntry;
import dev.evvie.waylandcraft.shared.SharedWindowManager;
import dev.evvie.waylandcraft.shared.WindowPermission;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 共享窗口"最新帧缓存 + 批量转发"器（v0.2.30：转发彻底移出 Server thread）。
 * 
 * 背景：
 * - v0.2.29 已把"收到帧立刻转发"改成"tick 批量转发"，但 ServerPlayNetworking.send
 *   仍在 Server thread（END_SERVER_TICK 回调）里执行。弱机器上 450KB+ 大帧的
 *   encode/write 仍会拖累服务端主线程，导致 Can't keep up 十几秒、发送端 keep-alive
 *   超时被踢（Timed out）。
 * 
 * v0.2.30 方案：
 * - receiver（netty 线程）只做权限校验，把最新帧放入缓存，绝不 send。
 * - 服务端 tick 每 2 tick（约 50ms）只做"轻量收集"：取出待转发帧 + 复制 players
 *   快照（主线程上读取玩家列表绝对安全），然后提交给独立 daemon 转发线程。
 * - 实际 send 全部在"waylandcraft-frame-relay"线程执行，Server thread 零转发负担。
 * - 缓存按 windowHandle 覆盖旧帧 = 丢中间帧；迭代器逐个 remove 取出，
 *   不用 clear()，避免并发 put 的新帧被误删，保证不丢"最新帧"。
 */
public class SharedWindowFrameRelay {

	/** 按 windowHandle 缓存每个窗口的最新帧；put 覆盖旧帧 = 丢中间帧 */
	private final Map<Long, SharedWindowImagePayload> latestFrames = new ConcurrentHashMap<>();

	/** MC CustomPacketPayload 约 2MB 包大小上限保护：超过该字节数告警并跳过 */
	private static final int MAX_FRAME_BYTES = 1_900_000;

	/** 转发间隔：每 2 tick 转发一次（约 50ms = 20fps 批次） */
	private static final int RELAY_INTERVAL_TICKS = 2;

	/** 独立转发线程：图片发送绝不占用 Server thread（daemon，服务端停止自动退出） */
	private final ExecutorService relayExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "waylandcraft-frame-relay");
		t.setDaemon(true);
		return t;
	});

	/** 只在服务端线程读写 */
	private int tickCounter = 0;

	/**
	 * 注册服务端 tick 转发。必须在初始化流程里与 receiver 注册一起调用。
	 */
	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> relayExecutor.shutdownNow());
	}

	/**
	 * netty 线程调用：仅把最新帧放入缓存，这里绝不调用 ServerPlayNetworking.send。
	 * 由调用方（receiver）保证权限校验已通过。
	 */
	public void acceptFrame(SharedWindowImagePayload payload) {
		latestFrames.put(payload.windowHandle(), payload);
	}

	/**
	 * Server thread 回调：只做轻量收集 + 提交，绝不 send。
	 */
	private void onServerTick(MinecraftServer server) {
		tickCounter++;
		if (tickCounter < RELAY_INTERVAL_TICKS) return;
		tickCounter = 0;
		if (latestFrames.isEmpty()) return;

		// 主线程读取玩家列表并快照（主线程自身不会并发修改，安全；转发线程用快照避免 CME）
		List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());

		// 收集待转发帧（迭代器逐个 remove，不用 clear()：并发 put 的新帧不会被误删）
		List<SharedWindowImagePayload> frames = new ArrayList<>();
		Iterator<Map.Entry<Long, SharedWindowImagePayload>> it = latestFrames.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, SharedWindowImagePayload> frameEntry = it.next();
			it.remove();
			frames.add(frameEntry.getValue());
		}
		if (frames.isEmpty()) return;

		// 提交给独立转发线程；服务端停止时线程池关闭，丢弃本批即可
		try {
			relayExecutor.execute(() -> relayFrames(server, players, frames));
		} catch (RejectedExecutionException e) {
			// 服务端已停止，忽略
		}
	}

	/**
	 * 转发线程：实际发送所有帧。绝不阻塞 Server thread。
	 */
	private void relayFrames(MinecraftServer server, List<ServerPlayer> players, List<SharedWindowImagePayload> frames) {
		SharedWindowManager manager = WaylandCraftCommon.instance.sharedWindowManager;

		for (SharedWindowImagePayload payload : frames) {
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
