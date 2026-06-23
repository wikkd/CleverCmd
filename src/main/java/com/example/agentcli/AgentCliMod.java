package com.example.agentcli;

import com.example.agentcli.agent.AgentCommands;
import com.example.agentcli.agent.AgentConfirmationPolicy;
import com.example.agentcli.agent.AgentService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Mod 入口:在 {@code onInitialize} 注册命令、监听 {@code SERVER_STARTED}/{@code SERVER_STOPPING}
 * 完成 Agent 状态加载/落盘。
 */
public class AgentCliMod implements ModInitializer {
	@Override
	public void onInitialize() {
		AgentService.initialize();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> AgentCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			AgentConfirmationPolicy.loadFromClientConfig();
			AgentService.get().load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AgentService.get().save(server));
	}
}
