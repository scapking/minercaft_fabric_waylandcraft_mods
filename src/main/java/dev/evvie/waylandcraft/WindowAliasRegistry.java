package dev.evvie.waylandcraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 窗口实例别名注册表。
 * 
 * 为每个 toplevel 分配一个会话内唯一的优雅别名（w1, w2, w3 …），
 * 通过 /wl list windows 直接可见、可直接用于所有 <handle> 参数。
 * 
 * 别名基于窗口出现顺序分配，会话（游戏进程）内保持稳定；
 * 重启后 handle 变化，别名也会重新分配 —— 与临时模板的语义一致。
 */
public class WindowAliasRegistry {

	private final Map<Long, String> aliasByHandle = new HashMap<>();
	private final Map<String, Long> handleByAlias = new HashMap<>();
	private int nextId = 1;

	/** 获取窗口别名，不存在则分配一个新的（w1, w2 …） */
	public String getOrCreate(long handle) {
		String alias = aliasByHandle.get(handle);
		if(alias != null) return alias;

		alias = "w" + (nextId++);
		aliasByHandle.put(handle, alias);
		handleByAlias.put(alias, handle);
		return alias;
	}

	/** 获取已有别名，没有则返回 null */
	public String get(long handle) {
		return aliasByHandle.get(handle);
	}

	/** 别名 -> handle，未注册返回 null */
	public Long resolve(String alias) {
		return handleByAlias.get(alias);
	}

	/** 清理已消失窗口的别名映射（编号不回收，保持会话内唯一稳定） */
	public void cleanup(Set<Long> aliveHandles) {
		List<String> dead = new ArrayList<>();
		for(Map.Entry<String, Long> e : handleByAlias.entrySet()) {
			if(!aliveHandles.contains(e.getValue())) dead.add(e.getKey());
		}
		for(String alias : dead) {
			Long handle = handleByAlias.remove(alias);
			if(handle != null) aliasByHandle.remove(handle);
		}
	}

}
