package dev.evvie.waylandcraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.desktop.DesktopEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 窗口模板管理：把一组窗口的世界位置记录成模板，方便恢复/复现。
 *
 * 临时模板（内存）：保存玩家所在区块内所有已显示窗口的位置 + 当前 handle，
 *   重启后失效（handle 会变）。
 * 永久模板（磁盘 templates.json）：记录 appId + 位置 + 朝向 + 缩放 + 分辨率，
 *   应用时自动启动对应应用并按记录放置，相当于"复现脚本"。
 */
public class WindowTemplateManager {

	public static class WindowTemplateEntry {
		/** 临时模板：会话内实例别名（w3） */
		public String alias = "";
		/** 永久模板：应用名 appId */
		public String appId = "";
		/** 临时模板：当前 handle（重启失效） */
		public long handle = 0;
		/** 窗口中心点世界坐标 */
		public double x, y, z;
		/** 窗口法线（朝向） */
		public double nx, ny, nz;
		/** 视觉缩放 */
		public double scale = 1.0;
		/** 窗口分辨率（px） */
		public int width, height;
	}

	public static class WindowTemplate {
		public String name = "";
		public List<WindowTemplateEntry> entries = new ArrayList<>();
	}

	private static final long APPLY_TIMEOUT_MS = 20000;

	private final Map<String, WindowTemplate> temporary = new LinkedHashMap<>();
	private final Map<String, WindowTemplate> permanent = new LinkedHashMap<>();
	private final List<PendingApply> pendingApplies = new ArrayList<>();

	private File templateFile;
	private boolean initialized = false;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private static class PendingApply {
		final WindowTemplateEntry entry;
		final long deadline;
		PendingApply(WindowTemplateEntry entry, long deadline) {
			this.entry = entry;
			this.deadline = deadline;
		}
	}

	public void init(File gameDir) {
		if(initialized) return;
		initialized = true;
		templateFile = new File(gameDir, "waylandcraft/templates.json");
		loadPermanent();
	}

	private void loadPermanent() {
		if(templateFile == null || !templateFile.exists()) return;
		try(FileReader reader = new FileReader(templateFile)) {
			PermanentStore store = gson.fromJson(reader, PermanentStore.class);
			if(store != null && store.templates != null) {
				for(WindowTemplate t : store.templates) {
					permanent.put(t.name, t);
				}
			}
		} catch(IOException e) {
			WaylandCraftCommon.LOGGER.error("Failed to read templates file!", e);
		}
	}

	private void savePermanentFile() {
		if(templateFile == null) return;
		PermanentStore store = new PermanentStore();
		store.templates = new ArrayList<>(permanent.values());
		try {
			if(!templateFile.getParentFile().exists()) templateFile.getParentFile().mkdirs();
			try(FileWriter writer = new FileWriter(templateFile)) {
				writer.write(gson.toJson(store));
			}
		} catch(IOException e) {
			WaylandCraftCommon.LOGGER.error("Failed to write templates file!", e);
		}
	}

	private static class PermanentStore {
		public List<WindowTemplate> templates = new ArrayList<>();
	}

	/** 收集玩家所在区块（16x16）内所有已显示窗口 */
	private List<WindowTemplateEntry> collectChunkEntries(WaylandCraft wlc) {
		List<WindowTemplateEntry> entries = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if(mc.player == null || wlc.bridge == null) return entries;

		BlockPos p = mc.player.blockPosition();
		int minX = (p.getX() >> 4) << 4;
		int minZ = (p.getZ() >> 4) << 4;
		int maxX = minX + 16;
		int maxZ = minZ + 16;

		for(WindowDisplay d : wlc.displays) {
			if(!(d.window instanceof WLCToplevel)) continue;
			Vec3 piv = d.pivot;
			if(piv.x < minX || piv.x >= maxX || piv.z < minZ || piv.z >= maxZ) continue;

			WLCToplevel t = (WLCToplevel) d.window;
			WindowTemplateEntry e = new WindowTemplateEntry();
			e.alias = wlc.windowAliases.getOrCreate(t.getHandle());
			e.appId = t.appID != null ? t.appID : "";
			e.handle = t.getHandle();
			e.x = piv.x;
			e.y = piv.y;
			e.z = piv.z;
			Vec3 n = d.normal();
			e.nx = n.x;
			e.ny = n.y;
			e.nz = n.z;
			e.scale = d.viewScale;
			e.width = t.geometry.width();
			e.height = t.geometry.height();
			entries.add(e);
		}
		return entries;
	}

	/** 保存临时模板（内存，重启失效） */
	public @Nullable WindowTemplate saveTemporary(String name, WaylandCraft wlc) {
		List<WindowTemplateEntry> entries = collectChunkEntries(wlc);
		WindowTemplate tpl = new WindowTemplate();
		tpl.name = name;
		tpl.entries = entries;
		temporary.put(name, tpl);
		return tpl;
	}

	/** 保存永久模板（磁盘，记录 appId + 位置 + 分辨率） */
	public @Nullable WindowTemplate savePermanent(String name, WaylandCraft wlc) {
		List<WindowTemplateEntry> entries = collectChunkEntries(wlc);
		WindowTemplate tpl = new WindowTemplate();
		tpl.name = name;
		tpl.entries = entries;
		permanent.put(name, tpl);
		savePermanentFile();
		return tpl;
	}

	/** 应用临时模板：按 handle/实例别名恢复窗口位置 */
	public boolean applyTemporary(String name, WaylandCraft wlc) {
		WindowTemplate tpl = temporary.get(name);
		if(tpl == null || wlc.bridge == null) return false;

		int applied = 0;
		for(WindowTemplateEntry e : tpl.entries) {
			WLCToplevel t = wlc.bridge.getToplevel(e.handle);
			if(t == null && e.alias != null && !e.alias.isEmpty()) {
				Long h = wlc.windowAliases.resolve(e.alias);
				if(h != null) t = wlc.bridge.getToplevel(h);
			}
			if(t != null) {
				applyEntry(wlc, e, t);
				applied++;
			}
		}
		return applied > 0;
	}

	/** 应用永久模板：窗口已开则直接放置，未开则启动应用并等待出现 */
	public boolean applyPermanent(String name, WaylandCraft wlc) {
		WindowTemplate tpl = permanent.get(name);
		if(tpl == null || wlc.bridge == null || wlc.xdgManager == null) return false;

		int applied = 0;
		int launched = 0;
		for(WindowTemplateEntry e : tpl.entries) {
			WLCToplevel t = findToplevelByAppId(wlc, e.appId);
			if(t != null) {
				applyEntry(wlc, e, t);
				applied++;
				continue;
			}
			DesktopEntry entry = wlc.xdgManager.forAppId(e.appId);
			if(entry != null) {
				if(wlc.bridge.execApp(entry.appId)) {
					pendingApplies.add(new PendingApply(e, System.currentTimeMillis() + APPLY_TIMEOUT_MS));
					launched++;
				}
			}
		}
		return applied > 0 || launched > 0;
	}

	/** 把模板条目应用到窗口：resize + 放置 + 朝向 + 缩放 */
	private void applyEntry(WaylandCraft wlc, WindowTemplateEntry e, WLCToplevel t) {
		if(e.width > 0 && e.height > 0
				&& (t.geometry.width() != e.width || t.geometry.height() != e.height)) {
			wlc.bridge.resizeToplevel(t, e.width, e.height);
		}

		WindowDisplay d = wlc.getOrCreateDisplay(t);
		d.pivot = new Vec3(e.x, e.y, e.z);

		Vec3 n = new Vec3(e.nx, e.ny, e.nz);
		if(n.lengthSqr() < 1e-6) n = new Vec3(0, 0, 1);
		d.rotate(n.normalize(), new Vec3(0, -1, 0));
		if(e.scale > 0.01) d.viewScale = e.scale;
		d.clampVertical();
	}

	/** 每 tick 处理等待窗口出现的永久模板应用 */
	public void tick(WaylandCraft wlc) {
		if(pendingApplies.isEmpty() || wlc.bridge == null) return;

		Iterator<PendingApply> it = pendingApplies.iterator();
		while(it.hasNext()) {
			PendingApply pa = it.next();
			WLCToplevel t = findToplevelByAppId(wlc, pa.entry.appId);
			if(t != null && t.isMapped()) {
				applyEntry(wlc, pa.entry, t);
				it.remove();
				continue;
			}
			if(System.currentTimeMillis() > pa.deadline) {
				it.remove();
			}
		}
	}

	/** 是否有正在等待的模板应用（用于提示） */
	public boolean hasPending() {
		return !pendingApplies.isEmpty();
	}

	@Nullable
	private static WLCToplevel findToplevelByAppId(WaylandCraft wlc, String appId) {
		if(appId == null || appId.isEmpty()) return null;
		for(WLCToplevel t : wlc.bridge.getToplevels()) {
			if(appId.equals(t.appID)) return t;
		}
		return null;
	}

	public List<WindowTemplate> listTemporary() {
		return new ArrayList<>(temporary.values());
	}

	public List<WindowTemplate> listPermanent() {
		return new ArrayList<>(permanent.values());
	}

	public boolean removeTemporary(String name) {
		return temporary.remove(name) != null;
	}

	public boolean removePermanent(String name) {
		boolean removed = permanent.remove(name) != null;
		if(removed) savePermanentFile();
		return removed;
	}

}
