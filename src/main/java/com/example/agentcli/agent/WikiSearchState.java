package com.example.agentcli.agent;

/**
 * Wiki 服务的三态机:
 * <ul>
 *   <li>{@link #DISABLED}   - 配置/工厂主动禁用</li>
 *   <li>{@link #AVAILABLE}  - 可用,允许发出请求</li>
 *   <li>{@link #UNAVAILABLE} - 因网络/超时连续失败,5 分钟冷却期内不再尝试</li>
 * </ul>
 */
public enum WikiSearchState {
	DISABLED,
	AVAILABLE,
	UNAVAILABLE
}
