package com.example.agentcli.agent;

import java.time.Instant;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;

public record AgentSession(
	UUID sessionId,
	UUID playerUuid,
	String rawText,
	IntentParseResult parseResult,
	ActionPlan plan,
	PolicyDecision policyDecision,
	SessionStatus status,
	String message,
	Instant createdAt,
	Instant updatedAt
) {
	public NbtCompound toNbt() {
		NbtCompound compound = new NbtCompound();
		compound.putString("sessionId", sessionId.toString());
		compound.putString("playerUuid", playerUuid.toString());
		compound.putString("rawText", rawText);
		compound.putString("status", status.name());
		compound.putString("message", message);
		compound.putLong("createdAt", createdAt.toEpochMilli());
		compound.putLong("updatedAt", updatedAt.toEpochMilli());
		if (parseResult != null) {
			compound.putString("normalizedText", parseResult.normalizedText());
			compound.putBoolean("clarificationRequired", parseResult.clarificationRequired());
			compound.putString("clarificationPrompt", parseResult.clarificationPrompt());
		}
		if (plan != null) {
			compound.put("plan", plan.toNbt());
		}
		if (policyDecision != null) {
			compound.put("policyDecision", policyDecision.toNbt());
		}
		return compound;
	}

	public static AgentSession fromNbt(NbtCompound compound) {
		IntentParseResult parseResult = new IntentParseResult(
			compound.getString("normalizedText"),
			java.util.List.of(),
			compound.getBoolean("clarificationRequired"),
			compound.getString("clarificationPrompt")
		);
		ActionPlan plan = compound.contains("plan") ? ActionPlan.fromNbt(compound.getCompound("plan")) : null;
		PolicyDecision policyDecision = compound.contains("policyDecision") ? PolicyDecision.fromNbt(compound.getCompound("policyDecision")) : null;
		return new AgentSession(
			UUID.fromString(compound.getString("sessionId")),
			UUID.fromString(compound.getString("playerUuid")),
			compound.getString("rawText"),
			parseResult,
			plan,
			policyDecision,
			SessionStatus.valueOf(compound.getString("status")),
			compound.getString("message"),
			Instant.ofEpochMilli(compound.getLong("createdAt")),
			Instant.ofEpochMilli(compound.getLong("updatedAt"))
		);
	}
}

