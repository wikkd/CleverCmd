package com.example.agentcli.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

public record ActionPlan(
	UUID planId,
	UUID sessionId,
	UUID playerUuid,
	String rawText,
	String normalizedText,
	ActionType actionType,
	List<ActionStep> steps,
	RiskLevel riskLevel,
	boolean requiresConfirmation,
	boolean reversible,
	Instant createdAt
) {
	public NbtCompound toNbt() {
		NbtCompound compound = new NbtCompound();
		compound.putString("planId", planId.toString());
		compound.putString("sessionId", sessionId.toString());
		compound.putString("playerUuid", playerUuid.toString());
		compound.putString("rawText", rawText);
		compound.putString("normalizedText", normalizedText);
		compound.putString("actionType", actionType.name());
		compound.putString("riskLevel", riskLevel.name());
		compound.putBoolean("requiresConfirmation", requiresConfirmation);
		compound.putBoolean("reversible", reversible);
		compound.putLong("createdAt", createdAt.toEpochMilli());
		NbtList stepsNbt = new NbtList();
		for (ActionStep step : steps) {
			stepsNbt.add(step.toNbt());
		}
		compound.put("steps", stepsNbt);
		return compound;
	}

	public static ActionPlan fromNbt(NbtCompound compound) {
		NbtList stepsNbt = compound.contains("steps", NbtElement.LIST_TYPE)
			? compound.getList("steps", NbtElement.COMPOUND_TYPE)
			: new NbtList();
		List<ActionStep> steps = new ArrayList<>();
		for (int i = 0; i < stepsNbt.size(); i++) {
			steps.add(ActionStep.fromNbt(stepsNbt.getCompound(i)));
		}
		return new ActionPlan(
			UUID.fromString(compound.getString("planId")),
			UUID.fromString(compound.getString("sessionId")),
			UUID.fromString(compound.getString("playerUuid")),
			compound.getString("rawText"),
			compound.getString("normalizedText"),
			ActionType.valueOf(compound.getString("actionType")),
			steps,
			RiskLevel.valueOf(compound.getString("riskLevel")),
			compound.getBoolean("requiresConfirmation"),
			compound.getBoolean("reversible"),
			Instant.ofEpochMilli(compound.getLong("createdAt"))
		);
	}

	public static ActionPlan singleStep(
		UUID sessionId,
		UUID playerUuid,
		String rawText,
		String normalizedText,
		ActionType actionType,
		Map<String, String> parameters,
		RiskLevel riskLevel,
		boolean requiresConfirmation,
		boolean reversible,
		String description
	) {
		ActionStep step = new ActionStep(UUID.randomUUID(), actionType, parameters, reversible, description);
		return new ActionPlan(
			UUID.randomUUID(),
			sessionId,
			playerUuid,
			rawText,
			normalizedText,
			actionType,
			List.of(step),
			riskLevel,
			requiresConfirmation,
			reversible,
			Instant.now()
		);
	}
}
