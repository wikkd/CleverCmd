package com.example.agentcli.agent;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

public final class AgentExecutionEngine {
	public ExecutionResult execute(ServerCommandSource source, ActionPlan plan) {
		ServerPlayerEntity player = requirePlayer(source);
		return switch (plan.actionType()) {
			case HELP -> ExecutionResult.noOp("已显示帮助。");
			case STATUS -> ExecutionResult.noOp("当前状态: " + formatStatus(player));
			case UNDO -> ExecutionResult.noOp("请使用撤销命令执行最近一次可撤销动作。");
			case CANCEL -> ExecutionResult.noOp("请使用取消命令放弃当前待确认计划。");
			case TELEPORT_SELF -> executeTeleport(player, plan);
			case GIVE_ITEM -> executeGive(player, plan);
			case SET_TIME -> executeTime(player, plan);
			case SET_GAMEMODE -> executeGameMode(player, plan);
		};
	}

	public ExecutionResult undo(ServerCommandSource source, ExecutionRecord record) {
		ServerPlayerEntity player = requirePlayer(source);
		List<UndoAction> reversed = new ArrayList<>();
		for (int i = record.undoActions().size() - 1; i >= 0; i--) {
			UndoAction action = record.undoActions().get(i);
			ExecutionResult result = switch (action.actionType()) {
				case TELEPORT_SELF -> undoTeleport(player, action.parameters());
				case GIVE_ITEM -> undoGive(player, action.parameters());
				case SET_TIME -> undoTime(player, action.parameters());
				case SET_GAMEMODE -> undoGameMode(player, action.parameters());
				default -> ExecutionResult.failed("该动作不可撤销: " + action.actionType().name());
			};
			if (result.status() == ExecutionStatus.FAILED) {
				return result;
			}
			reversed.add(action);
		}
		return ExecutionResult.success("已撤销最近一次可撤销动作。", reversed);
	}

	private static ExecutionResult executeTeleport(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		ServerWorld world = player.getServerWorld();
		double x;
		double y;
		double z;
		float yaw = player.getYaw();
		float pitch = player.getPitch();

		if ("spawn".equals(params.get("target"))) {
			var pos = world.getSpawnPos();
			x = pos.getX() + 0.5;
			y = pos.getY();
			z = pos.getZ() + 0.5;
		} else if ("origin".equals(params.get("target"))) {
			x = 0.5;
			y = Math.max(world.getBottomY() + 2, 80);
			z = 0.5;
		} else {
			x = Double.parseDouble(params.getOrDefault("x", String.valueOf(player.getX())));
			y = Double.parseDouble(params.getOrDefault("y", String.valueOf(player.getY())));
			z = Double.parseDouble(params.getOrDefault("z", String.valueOf(player.getZ())));
		}

		UndoAction undo = new UndoAction(
			ActionType.TELEPORT_SELF,
			Map.of(
				"dimension", player.getWorld().getRegistryKey().getValue().toString(),
				"x", Double.toString(player.getX()),
				"y", Double.toString(player.getY()),
				"z", Double.toString(player.getZ()),
				"yaw", Float.toString(player.getYaw()),
				"pitch", Float.toString(player.getPitch())
			),
			"恢复传送前位置"
		);

		player.teleport(world, x, y, z, yaw, pitch);
		return ExecutionResult.success("已传送到目标位置。", List.of(undo));
	}

	private static ExecutionResult executeGive(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		Identifier itemId = Identifier.tryParse(params.get("item"));
		if (itemId == null || !Registries.ITEM.containsId(itemId)) {
			return ExecutionResult.failed("未识别的物品: " + params.get("item"));
		}
		int count;
		try {
			count = Math.max(1, Math.min(64, Integer.parseInt(params.getOrDefault("count", "1"))));
		} catch (NumberFormatException e) {
			return ExecutionResult.failed("物品数量无效。");
		}
		var item = Registries.ITEM.get(itemId);
		player.giveItemStack(new ItemStack(item, count));
		UndoAction undo = new UndoAction(ActionType.GIVE_ITEM, Map.of("item", itemId.toString(), "count", Integer.toString(count)), "移除刚刚给予的物品");
		return ExecutionResult.success("已给予 " + count + " 个 " + itemId.getPath() + "。", List.of(undo));
	}

	private static ExecutionResult executeTime(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String time = params.get("time");
		ServerWorld world = player.getServerWorld();
		long current = world.getTimeOfDay();
		long target = switch (time) {
			case "day" -> 1000L;
			case "noon" -> 6000L;
			case "night" -> 13000L;
			case "midnight" -> 18000L;
			default -> current;
		};
		world.setTimeOfDay(target);
		UndoAction undo = new UndoAction(ActionType.SET_TIME, Map.of("time", Long.toString(current)), "恢复调整前时间");
		return ExecutionResult.success("世界时间已调整为 " + time + "。", List.of(undo));
	}

	private static ExecutionResult executeGameMode(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		GameMode mode = switch (params.get("mode")) {
			case "creative" -> GameMode.CREATIVE;
			case "survival" -> GameMode.SURVIVAL;
			case "adventure" -> GameMode.ADVENTURE;
			case "spectator" -> GameMode.SPECTATOR;
			default -> null;
		};
		if (mode == null) {
			return ExecutionResult.failed("未识别的游戏模式。");
		}
		GameMode previous = player.interactionManager.getGameMode();
		boolean ok = player.changeGameMode(mode);
		UndoAction undo = new UndoAction(ActionType.SET_GAMEMODE, Map.of("mode", previous.getName()), "恢复原游戏模式");
		return ok
			? ExecutionResult.success("游戏模式已切换为 " + mode.getName() + "。", List.of(undo))
			: ExecutionResult.failed("切换游戏模式失败。");
	}

	private static ExecutionResult undoTeleport(ServerPlayerEntity player, Map<String, String> params) {
		Identifier dimensionId = Identifier.tryParse(params.get("dimension"));
		if (dimensionId == null) {
			return ExecutionResult.failed("找不到用于撤销的维度。");
		}
		RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
		ServerWorld targetWorld = player.getServer().getWorld(worldKey);
		if (targetWorld == null) {
			return ExecutionResult.failed("找不到用于撤销的维度。");
		}
		player.teleport(
			targetWorld,
			Double.parseDouble(params.get("x")),
			Double.parseDouble(params.get("y")),
			Double.parseDouble(params.get("z")),
			Float.parseFloat(params.get("yaw")),
			Float.parseFloat(params.get("pitch"))
		);
		return ExecutionResult.success("已恢复传送前位置。", List.of());
	}

	private static ExecutionResult undoGive(ServerPlayerEntity player, Map<String, String> params) {
		Identifier itemId = Identifier.tryParse(params.get("item"));
		if (itemId == null || !Registries.ITEM.containsId(itemId)) {
			return ExecutionResult.failed("找不到用于撤销的物品。");
		}
		int remaining = Integer.parseInt(params.get("count"));
		var item = Registries.ITEM.get(itemId);
		for (int slot = 0; slot < player.getInventory().size() && remaining > 0; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(item)) {
				int removed = Math.min(remaining, stack.getCount());
				stack.decrement(removed);
				remaining -= removed;
				if (stack.isEmpty()) {
					player.getInventory().setStack(slot, ItemStack.EMPTY);
				}
			}
		}
		return remaining == 0
			? ExecutionResult.success("已撤销物品给予。", List.of())
			: ExecutionResult.partial("已部分撤销物品给予，仍有 " + remaining + " 个未能移除。", List.of());
	}

	private static ExecutionResult undoTime(ServerPlayerEntity player, Map<String, String> params) {
		long time = Long.parseLong(params.get("time"));
		player.getServerWorld().setTimeOfDay(time);
		return ExecutionResult.success("已恢复时间。", List.of());
	}

	private static ExecutionResult undoGameMode(ServerPlayerEntity player, Map<String, String> params) {
		GameMode mode = switch (params.get("mode")) {
			case "creative" -> GameMode.CREATIVE;
			case "survival" -> GameMode.SURVIVAL;
			case "adventure" -> GameMode.ADVENTURE;
			case "spectator" -> GameMode.SPECTATOR;
			default -> null;
		};
		if (mode == null) {
			return ExecutionResult.failed("找不到用于撤销的游戏模式。");
		}
		return player.changeGameMode(mode)
			? ExecutionResult.success("已恢复游戏模式。", List.of())
			: ExecutionResult.failed("恢复游戏模式失败。");
	}

	private static String formatStatus(ServerPlayerEntity player) {
		return String.format(java.util.Locale.ROOT, "%s @ %.1f %.1f %.1f in %s", player.getGameProfile().getName(), player.getX(), player.getY(), player.getZ(), player.getWorld().getRegistryKey().getValue());
	}

	private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
		try {
			return source.getPlayerOrThrow();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("Agent commands require a player source.", e);
		}
	}
}
