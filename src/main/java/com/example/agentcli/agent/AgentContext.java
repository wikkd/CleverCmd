package com.example.agentcli.agent;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

public record AgentContext(
	ServerCommandSource source,
	ServerPlayerEntity player,
	UUID playerUuid,
	String playerName,
	Vec3d position,
	String dimensionKey,
	GameMode gameMode,
	int permissionLevel
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
			source.hasPermissionLevel(2) ? 2 : 0
		);
	}

	public Text summaryText() {
		return Text.literal("位置=" + formatVec(position) + ", 维度=" + dimensionKey + ", 模式=" + gameMode.getName());
	}

	private static String formatVec(Vec3d vec) {
		return String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", vec.x, vec.y, vec.z);
	}
}
