package com.example.agentcli.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

public final class AgentPersistentStore {
	private final Map<UUID, AgentSession> pendingSessions = new HashMap<>();
	private final Map<UUID, Deque<ExecutionRecord>> histories = new HashMap<>();
	private Path file;

	public void load(MinecraftServer server) {
		file = server.getSavePath(WorldSavePath.ROOT).resolve("agentcli_state.dat");
		pendingSessions.clear();
		histories.clear();
		if (!Files.exists(file)) {
			return;
		}
		try {
			NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
			if (root == null) {
				return;
			}
			if (root.contains("pending", NbtElement.LIST_TYPE)) {
				NbtList pending = root.getList("pending", NbtElement.COMPOUND_TYPE);
				for (int i = 0; i < pending.size(); i++) {
					AgentSession session = AgentSession.fromNbt(pending.getCompound(i));
					pendingSessions.put(session.playerUuid(), session);
				}
			}
			if (root.contains("history", NbtElement.LIST_TYPE)) {
				NbtList history = root.getList("history", NbtElement.COMPOUND_TYPE);
				for (int i = 0; i < history.size(); i++) {
					ExecutionRecord record = ExecutionRecord.fromNbt(history.getCompound(i));
					histories.computeIfAbsent(record.playerUuid(), _ignored -> new ArrayDeque<>()).addLast(record);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("无法加载 Agent 状态", e);
		}
	}

	public void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			NbtCompound root = new NbtCompound();
			NbtList pending = new NbtList();
			for (AgentSession session : pendingSessions.values()) {
				pending.add(session.toNbt());
			}
			root.put("pending", pending);
			NbtList history = new NbtList();
			for (Deque<ExecutionRecord> queue : histories.values()) {
				for (ExecutionRecord record : queue) {
					history.add(record.toNbt());
				}
			}
			root.put("history", history);
			NbtIo.writeCompressed(root, file);
		} catch (IOException e) {
			throw new RuntimeException("无法保存 Agent 状态", e);
		}
	}

	public Optional<AgentSession> getPending(UUID playerUuid) {
		return Optional.ofNullable(pendingSessions.get(playerUuid));
	}

	public void putPending(AgentSession session) {
		pendingSessions.put(session.playerUuid(), session);
	}

	public void clearPending(UUID playerUuid) {
		pendingSessions.remove(playerUuid);
	}

	public void appendHistory(ExecutionRecord record) {
		Deque<ExecutionRecord> queue = histories.computeIfAbsent(record.playerUuid(), _ignored -> new ArrayDeque<>());
		queue.addLast(record);
		while (queue.size() > 20) {
			queue.removeFirst();
		}
	}

	public Optional<ExecutionRecord> findLastUndoable(UUID playerUuid) {
		Deque<ExecutionRecord> queue = histories.get(playerUuid);
		if (queue == null) {
			return Optional.empty();
		}
		List<ExecutionRecord> records = new ArrayList<>(queue);
		for (int i = records.size() - 1; i >= 0; i--) {
			ExecutionRecord record = records.get(i);
			if (record.undoable()) {
				return Optional.of(record);
			}
		}
		return Optional.empty();
	}
}
