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

/**
 * 每玩家最多 20 条执行历史的持久化层,文件位于 {@code <world>/agentcli_state.dat}。
 *
 * <p>提供 {@link #findLastUndoable(UUID)} 用于 {@code /agent undo};按从新到旧扫描,
 * 跳过不可逆的记录直到找到可撤销的一条。线程安全由调用方(命令 dispatcher 串行)保证,
 * 内部状态无显式锁。</p>
 */
public final class AgentPersistentStore {
	private final Map<UUID, Deque<ExecutionRecord>> histories = new HashMap<>();
	private Path file;

	public void load(MinecraftServer server) {
		file = server.getSavePath(WorldSavePath.ROOT).resolve("agentcli_state.dat");
		histories.clear();
		if (!Files.exists(file)) {
			return;
		}
		try {
			NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
			if (root == null) {
				return;
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
