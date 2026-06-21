package com.example.agentcli.agent;

import net.minecraft.nbt.NbtCompound;

public record PolicyDecision(
	boolean allowed,
	boolean requiresConfirmation,
	String reason,
	RiskLevel riskLevel,
	PermissionScope scope
) {
	public NbtCompound toNbt() {
		NbtCompound compound = new NbtCompound();
		compound.putBoolean("allowed", allowed);
		compound.putBoolean("requiresConfirmation", requiresConfirmation);
		compound.putString("reason", reason);
		compound.putString("riskLevel", riskLevel.name());
		compound.putString("scope", scope.name());
		return compound;
	}

	public static PolicyDecision fromNbt(NbtCompound compound) {
		return new PolicyDecision(
			compound.getBoolean("allowed"),
			compound.getBoolean("requiresConfirmation"),
			compound.getString("reason"),
			RiskLevel.valueOf(compound.getString("riskLevel")),
			PermissionScope.valueOf(compound.getString("scope"))
		);
	}
}

