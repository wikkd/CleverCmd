package com.example.agentcli.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class AgentConfirmationPolicyTest {
	@AfterEach
	void resetPolicy() {
		// 测试结束后恢复默认
		AgentConfirmationPolicy.replaceForTesting(null);
	}

	@Test
	void controlIntentsNeverRequireConfirmation() {
		AgentConfirmationPolicy.replaceForTesting(
				new AgentConfirmationPolicy(true, EnumSet.noneOf(ActionType.class)));

		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.HELP));
		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.STATUS));
		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.UNDO));
		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.CANCEL));
	}

	@Test
	void universalFlagOnMeansEverythingNeedsConfirm() {
		AgentConfirmationPolicy.replaceForTesting(
				new AgentConfirmationPolicy(true, EnumSet.noneOf(ActionType.class)));

		assertTrue(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.TELEPORT_SELF));
		assertTrue(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.RUN_COMMAND));
		assertTrue(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.HEAL));
	}

	@Test
	void universalFlagOffMeansNothingNeedsConfirm() {
		AgentConfirmationPolicy.replaceForTesting(
				new AgentConfirmationPolicy(false, EnumSet.noneOf(ActionType.class)));

		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.RUN_COMMAND));
		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.HEAL));
	}

	@Test
	void whitelistOverridesUniversalFlag() {
		// 全确认开关打开,但白名单包含 TELEPORT_SELF → 它仍然免确认
		AgentConfirmationPolicy.replaceForTesting(
				new AgentConfirmationPolicy(true, EnumSet.of(ActionType.TELEPORT_SELF)));

		assertFalse(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.TELEPORT_SELF));
		// 其他动作仍然要确认
		assertTrue(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.GIVE_EFFECT));
	}

	@Test
	void emptyWhitelistRespectsUniversalFlagOnly() {
		AgentConfirmationPolicy.replaceForTesting(
				new AgentConfirmationPolicy(true, Set.of()));

		assertTrue(AgentConfirmationPolicy.get().requiresConfirmation(ActionType.TELEPORT_SELF));
	}
}