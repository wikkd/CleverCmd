package com.example.agentcli.client;

import com.example.agentcli.client.config.AgentAiConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成:在 mod 列表页提供"配置"按钮,跳到 {@link AgentAiConfigScreen}。
 */
public final class AgentCliModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AgentAiConfigScreen::create;
	}
}
