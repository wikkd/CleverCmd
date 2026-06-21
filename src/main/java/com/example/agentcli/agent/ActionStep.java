package com.example.agentcli.agent;

import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

public record ActionStep(
	UUID stepId,
	ActionType actionType,
	Map<String, String> parameters,
	boolean reversible,
	String description
) {
	public NbtCompound toNbt() {
		NbtCompound compound = new NbtCompound();
		compound.putString("stepId", stepId.toString());
		compound.putString("actionType", actionType.name());
		compound.putBoolean("reversible", reversible);
		compound.putString("description", description);
		NbtCompound params = new NbtCompound();
		parameters.forEach(params::putString);
		compound.put("parameters", params);
		return compound;
	}

	public static ActionStep fromNbt(NbtCompound compound) {
		NbtCompound params = compound.contains("parameters", NbtElement.COMPOUND_TYPE)
			? compound.getCompound("parameters")
			: new NbtCompound();
		Map<String, String> parameters = params.getKeys().stream().collect(java.util.stream.Collectors.toMap(key -> key, params::getString));
		return new ActionStep(
			UUID.fromString(compound.getString("stepId")),
			ActionType.valueOf(compound.getString("actionType")),
			parameters,
			compound.getBoolean("reversible"),
			compound.getString("description")
		);
	}
}

