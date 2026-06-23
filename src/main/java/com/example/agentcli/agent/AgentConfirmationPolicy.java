package com.example.agentcli.agent;

import com.example.agentcli.client.config.AiConfigData;
import com.example.agentcli.client.config.AiConfigStore;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 服务端单例:根据客户端配置决定每个 ActionType 是否仍需 /agent T 确认。
 *
 * <p>生产路径:每次判断时直接从 {@link AiConfigStore} 读取最新配置(TTL 1 秒)。
 * Cloth Config 保存后,下一次玩家提交即生效,无需重启世界。</p>
 *
 * <p>测试路径:通过 {@link #replaceForTesting} 注入指定 (universal, whitelist) 元组,
 * 此时 {@link #requiresConfirmation} 使用注入值而不是 AiConfigStore。</p>
 */
public final class AgentConfirmationPolicy {
	private static volatile AgentConfirmationPolicy INSTANCE = new AgentConfirmationPolicy(true, EnumSet.noneOf(ActionType.class));

	private final boolean requireUniversalConfirmation;
	private final Set<ActionType> autoApproveActions;
	private final boolean isTestInstance;

	AgentConfirmationPolicy(boolean requireUniversalConfirmation, Set<ActionType> autoApproveActions) {
		this.requireUniversalConfirmation = requireUniversalConfirmation;
		this.autoApproveActions = autoApproveActions;
		this.isTestInstance = true;
	}

	public static AgentConfirmationPolicy get() {
		return INSTANCE;
	}

	// 测试辅助入口:替换默认实例。仅供单元测试使用。
	static void replaceForTesting(AgentConfirmationPolicy policy) {
		INSTANCE = policy == null ? new AgentConfirmationPolicy(true, EnumSet.noneOf(ActionType.class)) : policy;
	}

	/**
	 * 从客户端 AiConfigStore 加载当前策略。
	 * 当前实现下,生产路径无需在启动时加载(每次 reads 通过 AiConfigStore.get());
	 * 此方法保留仅为兼容旧调用方(如 AgentCliMod SERVER_STARTED 钩子)。
	 */
	public static void loadFromClientConfig() {
		// no-op: 见类注释。真正的读取发生在 requiresConfirmation() 内部。
	}

	/**
	 * 给定 ActionType 判断是否需要玩家确认。
	 * 优先级:白名单 > 开关 > 永远不确认(对控制类指令总是 false)。
	 *
	 * <p>测试实例:使用构造时注入的 (universal, whitelist)。<br>
	 * 生产实例:每次从 {@link AiConfigStore} 读取最新值。</p>
	 */
	public boolean requiresConfirmation(ActionType type) {
		if (type == null) {
			return false;
		}
		// 控制类指令不进入确认流程
		if (type == ActionType.HELP || type == ActionType.STATUS
				|| type == ActionType.UNDO || type == ActionType.CANCEL) {
			return false;
		}
		if (isTestInstance) {
			if (autoApproveActions.contains(type)) {
				return false;
			}
			return requireUniversalConfirmation;
		}
		AiConfigData config = AiConfigStore.get();
		if (config.isWhitelisted(type.name())) {
			return false;
		}
		return config.requireUniversalConfirmation();
	}

	public boolean isUniversalConfirmationRequired() {
		if (isTestInstance) {
			return requireUniversalConfirmation;
		}
		return AiConfigStore.get().requireUniversalConfirmation();
	}

	public Set<ActionType> autoApproveActions() {
		if (isTestInstance) {
			return Collections.unmodifiableSet(autoApproveActions);
		}
		AiConfigData config = AiConfigStore.get();
		Set<ActionType> whitelist = EnumSet.noneOf(ActionType.class);
		for (String name : config.whitelistedActions()) {
			try {
				whitelist.add(ActionType.valueOf(name));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return whitelist;
	}
}