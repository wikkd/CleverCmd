package com.example.agentcli.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

public record ExecutionRecord(
	UUID recordId,
	UUID sessionId,
	UUID playerUuid,
	String rawText,
	ActionPlan plan,
	ExecutionStatus status,
	String message,
	List<UndoAction> undoActions,
	Instant timestamp
) {
	public boolean undoable() {
		return !undoActions.isEmpty();
	}

	public NbtCompound toNbt() {
		NbtCompound compound = new NbtCompound();
		compound.putString("recordId", recordId.toString());
		compound.putString("sessionId", sessionId.toString());
		compound.putString("playerUuid", playerUuid.toString());
		compound.putString("rawText", rawText);
		compound.put("plan", plan.toNbt());
		compound.putString("status", status.name());
		compound.putString("message", message);
		compound.putLong("timestamp", timestamp.toEpochMilli());
		NbtList undoList = new NbtList();
		for (UndoAction undoAction : undoActions) {
			undoList.add(undoAction.toNbt());
		}
		compound.put("undoActions", undoList);
		return compound;
	}

	public static ExecutionRecord fromNbt(NbtCompound compound) {
		NbtList undoList = compound.contains("undoActions", NbtElement.LIST_TYPE)
			? compound.getList("undoActions", NbtElement.COMPOUND_TYPE)
			: new NbtList();
		List<UndoAction> undoActions = new ArrayList<>();
		for (int i = 0; i < undoList.size(); i++) {
			undoActions.add(UndoAction.fromNbt(undoList.getCompound(i)));
		}
		return new ExecutionRecord(
			UUID.fromString(compound.getString("recordId")),
			UUID.fromString(compound.getString("sessionId")),
			UUID.fromString(compound.getString("playerUuid")),
			compound.getString("rawText"),
			ActionPlan.fromNbt(compound.getCompound("plan")),
			ExecutionStatus.valueOf(compound.getString("status")),
			compound.getString("message"),
			undoActions,
			Instant.ofEpochMilli(compound.getLong("timestamp"))
		);
	}
}

