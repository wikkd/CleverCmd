package com.example.agentcli.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AiConfigDataTest {
	@Test
	void modelPresetFallsBackToDeepseekFlash() {
		assertEquals(AiModelPreset.DEEPSEEK_V4_FLASH, AiModelPreset.fromModelId(null));
		assertEquals(AiModelPreset.DEEPSEEK_V4_FLASH, AiModelPreset.fromModelId("unknown-model"));
	}

	@Test
	void configDataCopyKeepsAiFields() {
		AiConfigData original = new AiConfigData("https://api.example.com/v1", "secret-key", AiModelPreset.DEEPSEEK_V4_FLASH.modelId());

		AiConfigData copy = original.copy();

		assertEquals(original.apiBaseUrl(), copy.apiBaseUrl());
		assertEquals(original.apiKey(), copy.apiKey());
		assertEquals(original.modelId(), copy.modelId());
	}

	@Test
	void rawMinecraftCommandIsBlacklistedByDefault() {
		AiConfigData config = new AiConfigData();

		assertTrue(config.isBlacklisted("RUN_COMMAND"));
	}

	@Test
	void universalConfirmationIsRequiredByDefault() {
		AiConfigData config = new AiConfigData();

		assertTrue(config.requireUniversalConfirmation());
	}

	@Test
	void whitelistDefaultsToLowRiskActions() {
		AiConfigData config = new AiConfigData();

		assertTrue(config.isWhitelisted("TELEPORT_SELF"));
		assertTrue(config.isWhitelisted("GIVE_EFFECT"));
		assertTrue(config.isWhitelisted("SET_GAMEMODE"));
		// 新增的低风险白名单项
		assertTrue(config.isWhitelisted("SET_BLOCK"));
		assertTrue(config.isWhitelisted("TITLE_SELF"));
		assertTrue(config.isWhitelisted("TELLRAW_SELF"));
		assertFalse(config.isWhitelisted("CLEAR_INVENTORY"));
		assertFalse(config.isWhitelisted("RUN_COMMAND"));
	}

	@Test
	void copyPreservesConfirmationFields() {
		AiConfigData original = new AiConfigData();
		original.requireUniversalConfirmation(false);
		original.whitelistedActions(java.util.List.of("HEAL"));

		AiConfigData copy = original.copy();

		assertFalse(copy.requireUniversalConfirmation());
		assertTrue(copy.isWhitelisted("HEAL"));
		assertFalse(copy.isWhitelisted("TELEPORT_SELF"));
	}

	@Test
	void allowAdminCommandsDefaultsToFalse() {
		AiConfigData config = new AiConfigData();

		assertFalse(config.allowAdminCommands());
	}

	@Test
	void allowAdminCommandsCanBeToggled() {
		AiConfigData config = new AiConfigData();
		config.allowAdminCommands(true);

		assertTrue(config.allowAdminCommands());
	}

	@Test
	void copyPreservesAllowAdminCommands() {
		AiConfigData original = new AiConfigData();
		original.allowAdminCommands(true);

		AiConfigData copy = original.copy();

		assertTrue(copy.allowAdminCommands());
	}

	@Test
	void adminCommandsAreBlacklistedByDefault() {
		AiConfigData config = new AiConfigData();

		assertTrue(config.isBlacklisted("OP_PLAYER"));
		assertTrue(config.isBlacklisted("DEOP_PLAYER"));
		assertTrue(config.isBlacklisted("KICK_PLAYER"));
		assertTrue(config.isBlacklisted("BAN_PLAYER"));
		assertTrue(config.isBlacklisted("PARDON_PLAYER"));
		assertTrue(config.isBlacklisted("STOP_SERVER"));
		assertTrue(config.isBlacklisted("SAVE_ALL"));
	}

	@Test
	void worldLevelActionsAreBlacklistedByDefault() {
		AiConfigData config = new AiConfigData();

		assertTrue(config.isBlacklisted("BROADCAST_SAY"));
		assertTrue(config.isBlacklisted("GAMERULE_SET"));
		assertTrue(config.isBlacklisted("CLONE_REGION"));
		assertTrue(config.isBlacklisted("SCOREBOARD_ADD"));
		assertTrue(config.isBlacklisted("TEAM_ADD"));
		assertTrue(config.isBlacklisted("KILL_DROPS"));
	}

	@Test
	void blacklistRoundTripThroughCopy() {
		AiConfigData original = new AiConfigData();
		original.blacklistedActions(java.util.List.of("CUSTOM_X"));

		AiConfigData copy = original.copy();

		assertTrue(copy.isBlacklisted("CUSTOM_X"));
		assertFalse(copy.isBlacklisted("RUN_COMMAND"));
	}

	@Test
	void removingActionFromBlacklistMakesItPass() {
		// 模拟 Cloth Config 中移除黑名单项的流程
		AiConfigData config = new AiConfigData();
		assertTrue(config.isBlacklisted("RUN_COMMAND"));
		assertTrue(config.isBlacklisted("OP_PLAYER"));

		// 用户在 UI 中取消勾选这两项并保存
		config.blacklistedActions(java.util.List.of(
			"CLEAR_INVENTORY", "SET_TIME", "SET_WEATHER", "SPAWN_ENTITY"));

		assertFalse(config.isBlacklisted("RUN_COMMAND"));
		assertFalse(config.isBlacklisted("OP_PLAYER"));
		// 其他默认黑名单项仍然在
		assertTrue(config.isBlacklisted("CLEAR_INVENTORY"));
	}
}