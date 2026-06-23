package com.example.agentcli.agent;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.StringJoiner;
import java.util.UUID;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * 玩家在当前 tick 的快照,供 NLU 解析时携带位置/模式/血量/快捷栏摘要。
 *
 * <p>解析后端可读但不能改;每次 {@code /agent <text>} 提交时由
 * {@link #from(ServerCommandSource)} 重新构造,避免长会话内陈旧上下文。</p>
 */
public record AgentContext(
	ServerCommandSource source,
	ServerPlayerEntity player,
	UUID playerUuid,
	String playerName,
	Vec3d position,
	String dimensionKey,
	GameMode gameMode,
	int permissionLevel,
	float health,
	int foodLevel,
	String inventorySummary
) {
	public static AgentContext from(ServerCommandSource source) {
		ServerPlayerEntity player;
		try {
			player = source.getPlayerOrThrow();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("Agent commands require a player source.", e);
		}
		return new AgentContext(
			source,
			player,
			player.getUuid(),
			player.getName().getString(),
			player.getPos(),
			player.getWorld().getRegistryKey().getValue().toString(),
			player.interactionManager.getGameMode(),
			source.hasPermissionLevel(2) ? 2 : 0,
			player.getHealth(),
			player.getHungerManager().getFoodLevel(),
			buildInventorySummary(player)
		);
	}

	public Text summaryText() {
		return Text.literal("位置=" + formatVec(position) + ", 维度=" + dimensionKey + ", 模式=" + gameMode.getName());
	}

	private static String formatVec(Vec3d vec) {
		return String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", vec.x, vec.y, vec.z);
	}

	private static String buildInventorySummary(ServerPlayerEntity player) {
		StringJoiner joiner = new StringJoiner(", ");
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (!stack.isEmpty()) {
				String name = stack.getItem().toString();
				if (name.startsWith("minecraft:")) {
					name = name.substring("minecraft:".length());
				}
				joiner.add(name + "x" + stack.getCount());
			}
		}
		return joiner.length() == 0 ? "(空)" : joiner.toString();
	}
}
