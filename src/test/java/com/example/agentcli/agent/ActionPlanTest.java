package com.example.agentcli.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.junit.jupiter.api.Test;

final class ActionPlanTest {
	@Test
	void actionPlanRoundTripsThroughNbt() {
		UUID sessionId = UUID.randomUUID();
		UUID playerUuid = UUID.randomUUID();

		ActionPlan original = ActionPlan.singleStep(
			sessionId,
			playerUuid,
			"give me 3 diamond",
			"give me 3 diamond",
			ActionType.GIVE_ITEM,
			java.util.Map.of("item", "minecraft:diamond", "count", "3"),
			RiskLevel.LOW,
			true,
			true,
			"give item"
		);

		ActionPlan restored = ActionPlan.fromNbt(original.toNbt());

		assertEquals(original.planId(), restored.planId());
		assertEquals(original.sessionId(), restored.sessionId());
		assertEquals(original.playerUuid(), restored.playerUuid());
		assertEquals(original.rawText(), restored.rawText());
		assertEquals(original.normalizedText(), restored.normalizedText());
		assertEquals(original.actionType(), restored.actionType());
		assertEquals(original.riskLevel(), restored.riskLevel());
		assertEquals(original.requiresConfirmation(), restored.requiresConfirmation());
		assertEquals(original.reversible(), restored.reversible());
		assertEquals(original.steps().size(), restored.steps().size());
		assertEquals(original.steps().getFirst().parameters(), restored.steps().getFirst().parameters());
		assertEquals(original.steps().getFirst().description(), restored.steps().getFirst().description());
		assertEquals(original.createdAt().toEpochMilli(), restored.createdAt().toEpochMilli());
	}

	@Test
	void ruleBasedBackendParsesGiveIntent() {
		RuleBasedIntentBackend backend = new RuleBasedIntentBackend();
		AgentContext context = new AgentContext(
			null,
			null,
			UUID.randomUUID(),
			"Tester",
			new Vec3d(0.0, 64.0, 0.0),
			"minecraft:overworld",
			GameMode.SURVIVAL,
			2,
			20.0F,
			20,
			"(空)"
		);

		IntentParseResult result = backend.parse("give me 3 diamond", context, new AgentActionCatalog());

		assertFalse(result.clarificationRequired());
		assertNotNull(result.candidates());
		assertFalse(result.candidates().isEmpty());
		IntentCandidate best = result.candidates().getFirst();
		assertEquals(ActionType.GIVE_ITEM, best.actionType());
		assertEquals("minecraft:diamond", best.parameters().get("item"));
		assertEquals("3", best.parameters().get("count"));
		assertFalse(best.requiresConfirmation());
	}

	@Test
	void ruleBasedBackendParsesExplicitMinecraftCommand() {
		RuleBasedIntentBackend backend = new RuleBasedIntentBackend();
		AgentContext context = new AgentContext(
			null,
			null,
			UUID.randomUUID(),
			"Tester",
			new Vec3d(0.0, 64.0, 0.0),
			"minecraft:overworld",
			GameMode.SURVIVAL,
			2,
			20.0F,
			20,
			"(空)"
		);

		IntentParseResult result = backend.parse("执行 /gamerule keepInventory true", context, new AgentActionCatalog());

		assertFalse(result.clarificationRequired());
		IntentCandidate best = result.candidates().getFirst();
		assertEquals(ActionType.RUN_COMMAND, best.actionType());
		assertEquals("gamerule keepInventory true", best.parameters().get(MinecraftCommandSupport.COMMAND_PARAM));
		assertFalse(best.requiresConfirmation());
	}
}
