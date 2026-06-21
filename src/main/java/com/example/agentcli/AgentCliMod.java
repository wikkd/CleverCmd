package com.example.agentcli;

import com.example.agentcli.agent.AgentCommands;
import com.example.agentcli.agent.AgentService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class AgentCliMod implements ModInitializer {
	@Override
	public void onInitialize() {
		AgentService.initialize();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> AgentCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> AgentService.get().load(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AgentService.get().save(server));
	}
}
