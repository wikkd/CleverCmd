package com.example.agentcli.agent;

import java.util.Map;
import net.minecraft.nbt.NbtCompound;

public record UndoAction(ActionType actionType, Map<String, String> parameters, String description) {
	public NbtCompound toNbt() {
		NbtCompound compound = new NbtCompound();
		compound.putString("actionType", actionType.name());
		compound.putString("description", description);
		NbtCompound params = new NbtCompound();
		parameters.forEach(params::putString);
		compound.put("parameters", params);
		return compound;
	}

	public static UndoAction fromNbt(NbtCompound compound) {
		NbtCompound params = compound.getCompound("parameters");
		Map<String, String> map = params.getKeys().stream().collect(java.util.stream.Collectors.toMap(key -> key, params::getString));
		return new UndoAction(ActionType.valueOf(compound.getString("actionType")), map, compound.getString("description"));
	}
}

