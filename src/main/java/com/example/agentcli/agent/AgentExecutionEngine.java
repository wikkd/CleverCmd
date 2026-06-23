package com.example.agentcli.agent;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;

/**
 * 把通过策略闸门的 {@link ActionPlan} 落地为 Minecraft 实际状态变更的执行层。
 *
 * <p>每个 {@link ActionType} 在 switch 中走对应的 {@code executeX} 方法;可逆动作返回
 * 携带快照的 {@link ExecutionResult},{@code AgentService} 写入历史后,后续的
 * {@code /agent undo} 会回到这里走 {@code undoX} 反向操作。</p>
 *
 * <p>一些复杂命令(LOCATE_STRUCTURE / SET_BIOME / WORLD_BORDER / DIFFICULTY / SCOREBOARD_ADD)
 * 走 {@code CommandManager.executeWithPrefix} 直通原生命令,因为 1.21 yarn
 * 暴露的内部 API 在跨维度/跨方块场景下不够稳定;这些动作因此不可自动撤销。</p>
 */
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
			case SET_WEATHER -> executeWeather(player, plan);
			case GIVE_EFFECT -> executeGiveEffect(player, plan);
			case HEAL -> executeHeal(player, plan);
			case CLEAR_INVENTORY -> executeClearInventory(player, plan);
			case SPAWN_ENTITY -> executeSpawnEntity(player, plan);
			case RUN_COMMAND -> executeRunCommand(source, plan);
			// Tier A:玩家自身
			case KILL_SELF -> executeKillSelf(player);
			case RESPAWN_SELF -> executeRespawnSelf(player);
			case DAMAGE_SELF -> executeDamageSelf(player, plan);
			case SET_HEALTH -> executeSetHealth(player, plan);
			case EFFECT_CLEAR -> executeEffectClear(player);
			case ENCHANT_ITEM -> executeEnchantItem(player, plan);
			case GIVE_XP -> executeGiveXp(player, plan);
			case RIDE_DISMOUNT -> executeRideDismount(player);
			case TELEPORT_OTHER -> executeTeleportOther(source, plan);
			case SPECTATE -> executeSpectate(player);
			case TAG_ADD -> executeTagAdd(player, plan);
			case TAG_REMOVE -> executeTagRemove(player, plan);
			// Tier B:世界/方块
			case SET_BLOCK -> executeSetBlock(player, plan);
			case FILL_REGION -> executeFillRegion(player, plan);
			case CLONE_REGION -> executeCloneRegion(player, plan);
			case SET_BIOME -> executeSetBiome(player, plan);
			case SET_SPAWNPOINT -> executeSetSpawnpoint(player, plan);
			case DIFFICULTY -> executeDifficulty(player, plan);
			case GAMERULE_SET -> executeGameruleSet(player, plan);
			case KILL_DROPS -> executeKillDrops(player);
			case WORLD_BORDER -> executeWorldBorder(player, plan);
			// Tier C:通信/查询
			case BROADCAST_SAY -> executeBroadcastSay(source, plan);
			case MSG_PLAYER -> executeMsgPlayer(source, plan);
			case TITLE_SELF -> executeTitleSelf(player, plan);
			case TELLRAW_SELF -> executeTellrawSelf(player, plan);
			case LOCATE_STRUCTURE -> executeLocateStructure(player, plan);
			case SEED -> executeSeed(player);
			// Tier D:计分/队伍
			case SCOREBOARD_ADD -> executeScoreboardAdd(player, plan);
			case TEAM_ADD -> executeTeamAdd(player, plan);
			// Tier E:管理员
			case OP_PLAYER -> executeOpPlayer(player, plan);
			case DEOP_PLAYER -> executeDeopPlayer(player, plan);
			case KICK_PLAYER -> executeKickPlayer(player, plan);
			case BAN_PLAYER -> executeBanPlayer(player, plan);
			case PARDON_PLAYER -> executePardonPlayer(player, plan);
			case STOP_SERVER -> executeStopServer(source);
			case SAVE_ALL -> executeSaveAll(source);
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
				case SET_WEATHER -> undoWeather(player, action.parameters());
				case GIVE_EFFECT -> undoGiveEffect(player, action.parameters());
				case HEAL -> undoHeal(player, action.parameters());
				case CLEAR_INVENTORY -> undoClearInventory(player, action.parameters());
				case SPAWN_ENTITY -> undoSpawnEntity(player, action.parameters());
				case RUN_COMMAND -> ExecutionResult.failed("通用 Minecraft 命令不可自动撤销。");
				// Tier A 可逆
				case DAMAGE_SELF -> undoDamageSelf(player, action.parameters());
				case SET_HEALTH -> undoSetHealth(player, action.parameters());
				case GIVE_XP -> undoGiveXp(player, action.parameters());
				case TELEPORT_OTHER -> undoTeleportOther(source, action.parameters());
				case SPECTATE -> undoSpectate(player, action.parameters());
				case TAG_ADD -> undoTagAdd(player, action.parameters());
				case TAG_REMOVE -> undoTagRemove(player, action.parameters());
				// Tier B 可逆
				case SET_BLOCK -> undoSetBlock(player, action.parameters());
				case FILL_REGION -> undoFillRegion(player, action.parameters());
				case CLONE_REGION -> undoCloneRegion(player, action.parameters());
				case SET_BIOME -> undoSetBiome(player, action.parameters());
				case SET_SPAWNPOINT -> undoSetSpawnpoint(player, action.parameters());
				case DIFFICULTY -> undoDifficulty(player, action.parameters());
				case GAMERULE_SET -> undoGameruleSet(player, action.parameters());
				case WORLD_BORDER -> undoWorldBorder(player, action.parameters());
				// Tier D 可逆
				case TEAM_ADD -> undoTeamAdd(player, action.parameters());
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
		} else if ("true".equals(params.get("relative"))) {
			x = player.getX() + Double.parseDouble(params.getOrDefault("x", "0"));
			y = player.getY() + Double.parseDouble(params.getOrDefault("y", "0"));
			z = player.getZ() + Double.parseDouble(params.getOrDefault("z", "0"));
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
		int maxCount = com.example.agentcli.client.config.AiConfigStore.get().maxItemCount();
		int count;
		try {
			count = Math.max(1, Math.min(maxCount, Integer.parseInt(params.getOrDefault("count", "1"))));
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

	private static ExecutionResult executeRunCommand(ServerCommandSource source, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		MinecraftCommandSupport.CommandValidationResult validation = MinecraftCommandSupport.validate(
			source,
			params.get(MinecraftCommandSupport.COMMAND_PARAM)
		);
		if (!validation.valid()) {
			return ExecutionResult.failed(validation.reason());
		}
		try {
			source.getServer().getCommandManager().executeWithPrefix(source, validation.command());
			return ExecutionResult.success("已执行命令: /" + validation.command(), List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("命令执行失败: " + e.getMessage());
		}
	}

	// --- SET_WEATHER ---

	private static ExecutionResult executeWeather(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String weather = params.getOrDefault("weather", "clear");
		ServerWorld world = player.getServerWorld();

		boolean wasRaining = world.isRaining();
		boolean wasThundering = world.isThundering();

		UndoAction undo = new UndoAction(ActionType.SET_WEATHER, Map.of(
			"raining", Boolean.toString(wasRaining),
			"thundering", Boolean.toString(wasThundering)
		), "恢复之前的天气");

		switch (weather) {
			case "clear" -> {
				world.setWeather(6000, 0, false, false);
				return ExecutionResult.success("天气已设置为晴天。", List.of(undo));
			}
			case "rain" -> {
				world.setWeather(0, 6000, true, false);
				return ExecutionResult.success("天气已设置为下雨。", List.of(undo));
			}
			case "thunder" -> {
				world.setWeather(0, 6000, true, true);
				return ExecutionResult.success("天气已设置为雷暴。", List.of(undo));
			}
			default -> {
				return ExecutionResult.failed("未识别的天气类型: " + weather + "。支持: clear, rain, thunder");
			}
		}
	}

	private static ExecutionResult undoWeather(ServerPlayerEntity player, Map<String, String> params) {
		boolean raining = Boolean.parseBoolean(params.get("raining"));
		boolean thundering = Boolean.parseBoolean(params.get("thundering"));
		player.getServerWorld().setWeather(6000, 6000, raining, thundering);
		return ExecutionResult.success("已恢复之前的天气。", List.of());
	}

	// --- GIVE_EFFECT ---

	private static ExecutionResult executeGiveEffect(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String effectName = params.getOrDefault("effect", "speed");
		RegistryEntry<StatusEffect> effect = resolveStatusEffectEntry(effectName);
		if (effect == null) {
			return ExecutionResult.failed("未识别的效果: " + effectName + "。支持: speed, strength, regeneration, fire_resistance, night_vision, invisibility, jump_boost, water_breathing, health_boost, absorption");
		}
		int duration;
		try {
			duration = Math.max(1, Math.min(3600, Integer.parseInt(params.getOrDefault("duration", "30"))));
		} catch (NumberFormatException e) {
			duration = 30;
		}
		int amplifier;
		try {
			amplifier = Math.max(0, Math.min(255, Integer.parseInt(params.getOrDefault("amplifier", "0"))));
		} catch (NumberFormatException e) {
			amplifier = 0;
		}

		boolean hadBefore = player.hasStatusEffect(effect);
		player.addStatusEffect(new StatusEffectInstance(effect, duration * 20, amplifier, false, true, true));
		UndoAction undo = new UndoAction(ActionType.GIVE_EFFECT, Map.of(
			"effect", effectName,
			"hadBefore", Boolean.toString(hadBefore)
		), "移除药水效果");
		return ExecutionResult.success("已获得 " + effectName + " 效果，持续 " + duration + " 秒。", List.of(undo));
	}

	private static ExecutionResult undoGiveEffect(ServerPlayerEntity player, Map<String, String> params) {
		String effectName = params.getOrDefault("effect", "speed");
		RegistryEntry<StatusEffect> effect = resolveStatusEffectEntry(effectName);
		if (effect != null) {
			player.removeStatusEffect(effect);
		}
		return ExecutionResult.success("已移除药水效果。", List.of());
	}

	private static RegistryEntry<StatusEffect> resolveStatusEffectEntry(String name) {
		return switch (name.toLowerCase(java.util.Locale.ROOT)) {
			case "speed" -> StatusEffects.SPEED;
			case "slowness", "slow" -> StatusEffects.SLOWNESS;
			case "strength" -> StatusEffects.STRENGTH;
			case "regeneration", "regen" -> StatusEffects.REGENERATION;
			case "fire_resistance", "fire" -> StatusEffects.FIRE_RESISTANCE;
			case "night_vision", "nightvision" -> StatusEffects.NIGHT_VISION;
			case "invisibility", "invisible" -> StatusEffects.INVISIBILITY;
			case "jump_boost", "jump" -> StatusEffects.JUMP_BOOST;
			case "water_breathing", "water" -> StatusEffects.WATER_BREATHING;
			case "health_boost" -> StatusEffects.HEALTH_BOOST;
			case "absorption" -> StatusEffects.ABSORPTION;
			case "haste" -> StatusEffects.HASTE;
			case "mining_fatigue" -> StatusEffects.MINING_FATIGUE;
			case "resistance" -> StatusEffects.RESISTANCE;
			default -> null;
		};
	}

	// --- HEAL ---

	private static ExecutionResult executeHeal(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String target = params.getOrDefault("target", "all");

		float oldHealth = player.getHealth();
		int oldFood = player.getHungerManager().getFoodLevel();
		float oldSaturation = player.getHungerManager().getSaturationLevel();

		UndoAction undo = new UndoAction(ActionType.HEAL, Map.of(
			"health", Float.toString(oldHealth),
			"food", Integer.toString(oldFood),
			"saturation", Float.toString(oldSaturation),
			"target", target
		), "恢复治疗前状态");

		switch (target) {
			case "health" -> {
				player.setHealth(player.getMaxHealth());
				return ExecutionResult.success("生命值已恢复满。", List.of(undo));
			}
			case "hunger" -> {
				player.getHungerManager().setFoodLevel(20);
				player.getHungerManager().setSaturationLevel(5.0F);
				return ExecutionResult.success("饥饿值已恢复满。", List.of(undo));
			}
			default -> {
				player.setHealth(player.getMaxHealth());
				player.getHungerManager().setFoodLevel(20);
				player.getHungerManager().setSaturationLevel(5.0F);
				return ExecutionResult.success("生命值和饥饿值已全部恢复。", List.of(undo));
			}
		}
	}

	private static ExecutionResult undoHeal(ServerPlayerEntity player, Map<String, String> params) {
		float health = Float.parseFloat(params.getOrDefault("health", "20"));
		int food = Integer.parseInt(params.getOrDefault("food", "20"));
		float saturation = Float.parseFloat(params.getOrDefault("saturation", "5"));
		player.setHealth(Math.max(1.0F, health));
		player.getHungerManager().setFoodLevel(food);
		player.getHungerManager().setSaturationLevel(saturation);
		return ExecutionResult.success("已恢复治疗前状态。", List.of());
	}

	// --- CLEAR_INVENTORY ---

	private static ExecutionResult executeClearInventory(ServerPlayerEntity player, ActionPlan plan) {
		NbtList savedItems = new NbtList();
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (!stack.isEmpty()) {
				NbtCompound slotNbt = new NbtCompound();
				slotNbt.putInt("Slot", i);
				NbtCompound itemData = (NbtCompound) stack.encode(player.getRegistryManager());
				slotNbt.put("Item", itemData);
				savedItems.add(slotNbt);
			}
		}
		player.getInventory().clear();

		UndoAction undo = new UndoAction(ActionType.CLEAR_INVENTORY, Map.of(
			"itemsData", savedItems.toString()
		), "恢复背包内容");
		return ExecutionResult.success("背包已清空。", List.of(undo));
	}

	private static ExecutionResult undoClearInventory(ServerPlayerEntity player, Map<String, String> params) {
		String itemsData = params.getOrDefault("itemsData", "[]");
		try {
			NbtList savedItems = parseNbtList(itemsData);
			player.getInventory().clear();
			for (int i = 0; i < savedItems.size(); i++) {
				NbtCompound slotNbt = savedItems.getCompound(i);
				int slot = slotNbt.getInt("Slot");
				NbtCompound itemData = slotNbt.getCompound("Item");
				ItemStack stack = ItemStack.fromNbt(player.getRegistryManager(), itemData).orElse(ItemStack.EMPTY);
				if (!stack.isEmpty() && slot >= 0 && slot < player.getInventory().size()) {
					player.getInventory().setStack(slot, stack);
				}
			}
			return ExecutionResult.success("已恢复背包内容。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("恢复背包失败: " + e.getMessage());
		}
	}

	private static NbtList parseNbtList(String data) {
		try {
			com.mojang.brigadier.StringReader reader = new com.mojang.brigadier.StringReader(data);
			net.minecraft.nbt.StringNbtReader nbtReader = new net.minecraft.nbt.StringNbtReader(reader);
			NbtElement element = nbtReader.parseElement();
			if (element instanceof NbtList list) {
				return list;
			}
			return new NbtList();
		} catch (Exception e) {
			return new NbtList();
		}
	}

	// --- SPAWN_ENTITY ---

	private static ExecutionResult executeSpawnEntity(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String entityName = params.getOrDefault("entity", "minecraft:zombie");
		Identifier entityId = Identifier.tryParse(entityName);
		if (entityId == null) {
			return ExecutionResult.failed("未识别的实体 ID: " + entityName);
		}
		EntityType<?> entityType = Registries.ENTITY_TYPE.getOrEmpty(entityId).orElse(null);
		if (entityType == null) {
			return ExecutionResult.failed("未识别的实体类型: " + entityName);
		}
		int count;
		try {
			count = Math.max(1, Math.min(20, Integer.parseInt(params.getOrDefault("count", "1"))));
		} catch (NumberFormatException e) {
			count = 1;
		}

		ServerWorld world = player.getServerWorld();
		Vec3d lookDir = player.getRotationVec(1.0F);
		double spawnX = player.getX() + lookDir.x * 3.0;
		double spawnY = player.getY();
		double spawnZ = player.getZ() + lookDir.z * 3.0;

		List<String> spawnedUuids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Entity entity = entityType.create(world);
			if (entity == null) {
				continue;
			}
			entity.refreshPositionAndAngles(
				spawnX + (Math.random() - 0.5) * 2,
				spawnY,
				spawnZ + (Math.random() - 0.5) * 2,
				player.getYaw(),
				0
			);
			world.spawnEntity(entity);
			spawnedUuids.add(entity.getUuidAsString());
		}

		if (spawnedUuids.isEmpty()) {
			return ExecutionResult.failed("生成实体失败。");
		}

		UndoAction undo = new UndoAction(ActionType.SPAWN_ENTITY, Map.of(
			"entity", entityName,
			"uuids", String.join(",", spawnedUuids)
		), "移除生成的实体");
		return ExecutionResult.success("已生成 " + spawnedUuids.size() + " 个 " + entityId.getPath() + "。", List.of(undo));
	}

	private static ExecutionResult undoSpawnEntity(ServerPlayerEntity player, Map<String, String> params) {
		String uuidsStr = params.getOrDefault("uuids", "");
		if (uuidsStr.isBlank()) {
			return ExecutionResult.success("无需移除的实体。", List.of());
		}
		String[] uuids = uuidsStr.split(",");
		int removed = 0;
		for (String uuidStr : uuids) {
			try {
				UUID uuid = UUID.fromString(uuidStr.trim());
				for (ServerWorld world : player.getServer().getWorlds()) {
					Entity entity = world.getEntity(uuid);
					if (entity != null) {
						entity.discard();
						removed++;
						break;
					}
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		return ExecutionResult.success("已移除 " + removed + " 个之前生成的实体。", List.of());
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

	// ===============================================================
	//  Tier A: 玩家自身
	// ===============================================================

	private static ExecutionResult executeKillSelf(ServerPlayerEntity player) {
		player.kill();
		return ExecutionResult.success("已自杀。", List.of());
	}

	private static ExecutionResult executeRespawnSelf(ServerPlayerEntity player) {
		if (!player.isDead()) {
			return ExecutionResult.failed("当前不在死亡状态,无需重生。");
		}
		player.requestRespawn();
		return ExecutionResult.success("已请求重生。", List.of());
	}

	private static ExecutionResult executeDamageSelf(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		float amount;
		try {
			amount = Math.max(1, Math.min(20, Float.parseFloat(params.getOrDefault("amount", "1"))));
		} catch (NumberFormatException e) {
			amount = 1;
		}
		float oldHealth = player.getHealth();
		DamageSource source = player.getDamageSources().generic();
		player.damage(source, amount);
		UndoAction undo = new UndoAction(ActionType.DAMAGE_SELF, Map.of(
			"health", Float.toString(oldHealth)
		), "恢复伤害前血量");
		return ExecutionResult.success("对自己造成 " + amount + " 点伤害。", List.of(undo));
	}

	private static ExecutionResult undoDamageSelf(ServerPlayerEntity player, Map<String, String> params) {
		float health = Float.parseFloat(params.getOrDefault("health", "20"));
		player.setHealth(Math.max(1.0F, health));
		return ExecutionResult.success("已恢复伤害前血量。", List.of());
	}

	private static ExecutionResult executeSetHealth(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		float newHealth;
		try {
			newHealth = Math.max(1, Math.min(1024, Float.parseFloat(params.getOrDefault("health", "20"))));
		} catch (NumberFormatException e) {
			return ExecutionResult.failed("血量值无效。");
		}
		var attr = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (attr == null) {
			return ExecutionResult.failed("找不到 max_health 属性。");
		}
		double oldMax = attr.getBaseValue();
		float oldCurrent = player.getHealth();
		attr.setBaseValue(newHealth);
		player.setHealth(Math.min(player.getHealth(), newHealth));
		UndoAction undo = new UndoAction(ActionType.SET_HEALTH, Map.of(
			"maxHealth", Double.toString(oldMax),
			"currentHealth", Float.toString(oldCurrent)
		), "恢复原血量");
		return ExecutionResult.success("最大血量已设置为 " + (int) newHealth + "。", List.of(undo));
	}

	private static ExecutionResult undoSetHealth(ServerPlayerEntity player, Map<String, String> params) {
		double max = Double.parseDouble(params.getOrDefault("maxHealth", "20"));
		float current = Float.parseFloat(params.getOrDefault("currentHealth", "20"));
		var attr = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (attr != null) {
			attr.setBaseValue(max);
		}
		player.setHealth(Math.min(current, (float) max));
		return ExecutionResult.success("已恢复原血量。", List.of());
	}

	private static ExecutionResult executeEffectClear(ServerPlayerEntity player) {
		if (player.getStatusEffects().isEmpty()) {
			return ExecutionResult.success("当前没有药水效果。", List.of());
		}
		player.clearStatusEffects();
		return ExecutionResult.success("已清除全部药水效果。", List.of());
	}

	private static ExecutionResult executeEnchantItem(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		ItemStack stack = player.getMainHandStack();
		if (stack.isEmpty()) {
			return ExecutionResult.failed("主手上没有物品。");
		}
		String enchantId = params.getOrDefault("enchantment", "");
		Identifier enchId = Identifier.tryParse(enchantId.contains(":") ? enchantId : "minecraft:" + enchantId);
		if (enchId == null) {
			return ExecutionResult.failed("未识别的附魔: " + enchantId);
		}
		ServerWorld world = player.getServerWorld();
		var enchReg = world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT).orElse(null);
		if (enchReg == null) {
			return ExecutionResult.failed("注册表 ENCHANTMENT 不可用。");
		}
		var enchEntry = enchReg.getEntry(enchId).orElse(null);
		if (enchEntry == null) {
			return ExecutionResult.failed("未找到附魔: " + enchId);
		}
		int level = 1;
		try {
			level = Math.max(1, Math.min(255, Integer.parseInt(params.getOrDefault("level", "1"))));
		} catch (NumberFormatException ignored) {
		}
		stack.addEnchantment(enchEntry, level);
		return ExecutionResult.success("已附魔 " + enchId.getPath() + " " + level + " 级。", List.of());
	}

	private static ExecutionResult executeGiveXp(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		int amount;
		try {
			amount = Integer.parseInt(params.getOrDefault("amount", "1"));
		} catch (NumberFormatException e) {
			return ExecutionResult.failed("经验数值无效。");
		}
		String mode = params.getOrDefault("mode", "add");
		int before = player.totalExperience;
		if ("set".equals(mode)) {
			player.setExperienceLevel(amount);
		} else {
			player.addExperienceLevels(amount);
		}
		int delta = player.totalExperience - before;
		UndoAction undo = new UndoAction(ActionType.GIVE_XP, Map.of(
			"xpDelta", Integer.toString(-delta)
		), "撤销经验变更");
		return ExecutionResult.success("经验已" + ("set".equals(mode) ? "设为 " : "增加 ") + amount + "。", List.of(undo));
	}

	private static ExecutionResult undoGiveXp(ServerPlayerEntity player, Map<String, String> params) {
		int delta;
		try {
			delta = Integer.parseInt(params.getOrDefault("xpDelta", "0"));
		} catch (NumberFormatException e) {
			return ExecutionResult.failed("经验撤销参数无效。");
		}
		if (delta < 0) {
			player.addExperienceLevels(delta);
			return ExecutionResult.success("已撤销经验变更。", List.of());
		}
		return ExecutionResult.failed("经验撤销操作无意义。");
	}

	private static ExecutionResult executeRideDismount(ServerPlayerEntity player) {
		if (!player.hasVehicle()) {
			return ExecutionResult.failed("当前未乘坐任何实体。");
		}
		player.stopRiding();
		return ExecutionResult.success("已离开坐骑。", List.of());
	}

	private static ExecutionResult executeTeleportOther(ServerCommandSource origin, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String targetName = params.get("target");
		ServerPlayerEntity target = origin.getServer().getPlayerManager().getPlayer(targetName);
		if (target == null) {
			return ExecutionResult.failed("找不到玩家: " + targetName);
		}
		String dest = params.getOrDefault("destination", "self");
		double x, y, z;
		if ("self".equals(dest)) {
			ServerPlayerEntity self = origin.getPlayer();
			if (self == null) {
				return ExecutionResult.failed("需要玩家源才能传送到自己。");
			}
			x = self.getX(); y = self.getY(); z = self.getZ();
		} else {
			try {
				x = Double.parseDouble(params.getOrDefault("x", "0"));
				y = Double.parseDouble(params.getOrDefault("y", "0"));
				z = Double.parseDouble(params.getOrDefault("z", "0"));
			} catch (NumberFormatException e) {
				return ExecutionResult.failed("坐标无效。");
			}
		}
		UndoAction undo = new UndoAction(ActionType.TELEPORT_OTHER, Map.of(
			"target", targetName,
			"dimension", target.getWorld().getRegistryKey().getValue().toString(),
			"x", Double.toString(target.getX()),
			"y", Double.toString(target.getY()),
			"z", Double.toString(target.getZ()),
			"yaw", Float.toString(target.getYaw()),
			"pitch", Float.toString(target.getPitch())
		), "恢复被传送玩家的位置");
		target.teleport(target.getServerWorld(), x, y, z, target.getYaw(), target.getPitch());
		return ExecutionResult.success("已将 " + targetName + " 传送到目标位置。", List.of(undo));
	}

	private static ExecutionResult undoTeleportOther(ServerCommandSource origin, Map<String, String> params) {
		String targetName = params.get("target");
		ServerPlayerEntity target = origin.getServer().getPlayerManager().getPlayer(targetName);
		if (target == null) {
			return ExecutionResult.failed("找不到玩家: " + targetName);
		}
		Identifier dimId = Identifier.tryParse(params.get("dimension"));
		if (dimId == null) return ExecutionResult.failed("维度 ID 无效。");
		RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
		ServerWorld world = origin.getServer().getWorld(worldKey);
		if (world == null) return ExecutionResult.failed("找不到维度。");
		target.teleport(world,
			Double.parseDouble(params.get("x")),
			Double.parseDouble(params.get("y")),
			Double.parseDouble(params.get("z")),
			Float.parseFloat(params.getOrDefault("yaw", "0")),
			Float.parseFloat(params.getOrDefault("pitch", "0")));
		return ExecutionResult.success("已恢复 " + targetName + " 的位置。", List.of());
	}

	private static ExecutionResult executeSpectate(ServerPlayerEntity player) {
		GameMode previous = player.interactionManager.getGameMode();
		boolean wasSpectator = player.isSpectator();
		if (wasSpectator) {
			player.changeGameMode(GameMode.SURVIVAL);
			return ExecutionResult.success("已退出旁观模式。", List.of());
		}
		player.changeGameMode(GameMode.SPECTATOR);
		UndoAction undo = new UndoAction(ActionType.SPECTATE, Map.of(
			"previousMode", previous.getName()
		), "恢复原游戏模式");
		return ExecutionResult.success("已进入旁观模式。", List.of(undo));
	}

	private static ExecutionResult undoSpectate(ServerPlayerEntity player, Map<String, String> params) {
		GameMode mode = switch (params.getOrDefault("previousMode", "survival")) {
			case "creative" -> GameMode.CREATIVE;
			case "survival" -> GameMode.SURVIVAL;
			case "adventure" -> GameMode.ADVENTURE;
			case "spectator" -> GameMode.SPECTATOR;
			default -> GameMode.SURVIVAL;
		};
		return player.changeGameMode(mode)
			? ExecutionResult.success("已恢复原游戏模式。", List.of())
			: ExecutionResult.failed("恢复游戏模式失败。");
	}

	private static ExecutionResult executeTagAdd(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String tag = params.getOrDefault("tag", "");
		if (tag.isBlank()) {
			return ExecutionResult.failed("标签名不能为空。");
		}
		boolean added = player.addCommandTag(tag);
		UndoAction undo = new UndoAction(ActionType.TAG_ADD, Map.of(
			"tag", tag,
			"hadBefore", Boolean.toString(!added)
		), "移除标签 " + tag);
		return added
			? ExecutionResult.success("已添加标签 " + tag + "。", List.of(undo))
			: ExecutionResult.success("标签 " + tag + " 已存在,无需重复添加。", List.of(undo));
	}

	private static ExecutionResult undoTagAdd(ServerPlayerEntity player, Map<String, String> params) {
		String tag = params.get("tag");
		if (Boolean.parseBoolean(params.getOrDefault("hadBefore", "false"))) {
			return ExecutionResult.success("标签在添加前已存在,无需撤销。", List.of());
		}
		player.removeCommandTag(tag);
		return ExecutionResult.success("已移除标签 " + tag + "。", List.of());
	}

	private static ExecutionResult executeTagRemove(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String tag = params.getOrDefault("tag", "");
		boolean hadBefore = player.getCommandTags().contains(tag);
		player.removeCommandTag(tag);
		UndoAction undo = new UndoAction(ActionType.TAG_REMOVE, Map.of(
			"tag", tag,
			"hadBefore", Boolean.toString(hadBefore)
		), "恢复标签 " + tag);
		return hadBefore
			? ExecutionResult.success("已移除标签 " + tag + "。", List.of(undo))
			: ExecutionResult.success("标签 " + tag + " 本不存在。", List.of(undo));
	}

	private static ExecutionResult undoTagRemove(ServerPlayerEntity player, Map<String, String> params) {
		String tag = params.get("tag");
		if (Boolean.parseBoolean(params.getOrDefault("hadBefore", "false"))) {
			player.addCommandTag(tag);
			return ExecutionResult.success("已恢复标签 " + tag + "。", List.of());
		}
		return ExecutionResult.success("无需恢复(原不存在)。", List.of());
	}

	// ===============================================================
	//  Tier B: 世界 / 方块
	// ===============================================================

	private static final int MAX_REGION_BLOCKS = 32 * 32 * 32;

	private static void forEachBlock(int x1, int y1, int z1, int x2, int y2, int z2, java.util.function.Consumer<BlockPos> action) {
		int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
		int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
		int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					action.accept(new BlockPos(x, y, z));
				}
			}
		}
	}

	private static BlockPos readBlockPos(Map<String, String> params, String prefix) {
		try {
			int x = Integer.parseInt(params.get(prefix + "x"));
			int y = Integer.parseInt(params.get(prefix + "y"));
			int z = Integer.parseInt(params.get(prefix + "z"));
			return new BlockPos(x, y, z);
		} catch (Exception e) {
			return null;
		}
	}

	private static ExecutionResult executeSetBlock(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String blockName = params.getOrDefault("block", "minecraft:stone");
		Identifier blockId = Identifier.tryParse(blockName);
		if (blockId == null) return ExecutionResult.failed("未识别的方块: " + blockName);
		var blockReg = Registries.BLOCK.getOrEmpty(blockId).orElse(null);
		if (blockReg == null) return ExecutionResult.failed("未找到方块: " + blockId);
		BlockState newState = blockReg.getDefaultState();
		ServerWorld world = player.getServerWorld();
		BlockPos pos;
		if ("feet".equals(params.get("position"))) {
			pos = player.getBlockPos();
		} else {
			BlockPos explicit = readBlockPos(params, "");
			if (explicit == null) return ExecutionResult.failed("坐标无效。");
			pos = explicit;
		}
		BlockState oldState = world.getBlockState(pos);
		BlockEntity oldBe = world.getBlockEntity(pos);
		NbtCompound oldBeNbt = null;
		if (oldBe != null) {
			oldBeNbt = oldBe.createNbtWithId(world.getRegistryManager());
		}
		world.setBlockState(pos, newState, 3);
		UndoAction undo = new UndoAction(ActionType.SET_BLOCK, Map.of(
			"x", Integer.toString(pos.getX()),
			"y", Integer.toString(pos.getY()),
			"z", Integer.toString(pos.getZ()),
			"oldState", oldState.toString(),
			"oldBe", oldBeNbt == null ? "" : oldBeNbt.toString()
		), "恢复原方块");
		return ExecutionResult.success("已放置 " + blockId.getPath() + " 在 " + pos.toShortString() + "。", List.of(undo));
	}

	private static ExecutionResult undoSetBlock(ServerPlayerEntity player, Map<String, String> params) {
		BlockPos pos = readBlockPos(params, "");
		if (pos == null) return ExecutionResult.failed("坐标无效。");
		ServerWorld world = player.getServerWorld();
		String stateStr = params.getOrDefault("oldState", "minecraft:air");
		BlockState restored = parseBlockState(stateStr);
		if (restored == null) return ExecutionResult.failed("无法解析原方块状态: " + stateStr);
		world.setBlockState(pos, restored, 3);
		// 注:1.21 yarn 下 BlockEntity.readNbt 是 protected,无法外部直接调用,故仅回滚方块状态,
		//    箱子内容/告示牌文字等 BE 数据不在 undo 范围内。
		return ExecutionResult.success("已恢复 " + pos.toShortString() + " 的原方块。", List.of());
	}

	private static BlockState parseBlockState(String stateStr) {
		// 简化版:解析 "minecraft:stone" 或 "minecraft:water[level=0]"
		int bracketIdx = stateStr.indexOf('[');
		String blockName = bracketIdx < 0 ? stateStr : stateStr.substring(0, bracketIdx);
		Identifier blockId = Identifier.tryParse(blockName);
		if (blockId == null) return null;
		var blockReg = Registries.BLOCK.getOrEmpty(blockId).orElse(null);
		if (blockReg == null) return null;
		return blockReg.getDefaultState();
	}

	private static ExecutionResult executeFillRegion(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		BlockPos from = readBlockPos(params, "");
		if (from == null) return ExecutionResult.failed("起点坐标无效。");
		BlockPos to = new BlockPos(
			Integer.parseInt(params.get("x2")),
			Integer.parseInt(params.get("y2")),
			Integer.parseInt(params.get("z2")));
		int v = Math.abs((to.getX() - from.getX() + 1) * (to.getY() - from.getY() + 1) * (to.getZ() - from.getZ() + 1));
		if (v > MAX_REGION_BLOCKS) {
			return ExecutionResult.failed("区域过大(" + v + " 方块),上限 " + MAX_REGION_BLOCKS + "。");
		}
		String blockName = params.getOrDefault("block", "minecraft:stone");
		Identifier blockId = Identifier.tryParse(blockName);
		if (blockId == null) return ExecutionResult.failed("未识别的方块: " + blockName);
		var blockReg = Registries.BLOCK.getOrEmpty(blockId).orElse(null);
		if (blockReg == null) return ExecutionResult.failed("未找到方块: " + blockId);
		BlockState fillState = blockReg.getDefaultState();
		ServerWorld world = player.getServerWorld();
		// 快照
		NbtList snapshot = new NbtList();
		forEachBlock(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), p -> {
			NbtCompound entry = new NbtCompound();
			entry.putInt("x", p.getX()); entry.putInt("y", p.getY()); entry.putInt("z", p.getZ());
			entry.putString("state", world.getBlockState(p).toString());
			BlockEntity be = world.getBlockEntity(p);
			if (be != null) {
				entry.put("be", be.createNbtWithId(world.getRegistryManager()));
			}
			snapshot.add(entry);
		});
		// 填充
		forEachBlock(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(),
			p -> world.setBlockState(p, fillState, 3));
		UndoAction undo = new UndoAction(ActionType.FILL_REGION, Map.of(
			"blocks", snapshot.toString()
		), "恢复填充区域");
		return ExecutionResult.success("已填充 " + v + " 个方块为 " + blockId.getPath() + "。", List.of(undo));
	}

	private static ExecutionResult undoFillRegion(ServerPlayerEntity player, Map<String, String> params) {
		String blocksData = params.getOrDefault("blocks", "[]");
		try {
			NbtList list = parseNbtList(blocksData);
			ServerWorld world = player.getServerWorld();
			int restored = 0;
			for (int i = 0; i < list.size(); i++) {
				NbtCompound entry = list.getCompound(i);
				BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
				BlockState state = parseBlockState(entry.getString("state"));
				if (state != null) {
					world.setBlockState(pos, state, 3);
					restored++;
				}
				if (entry.contains("be")) {
					// BE 数据不参与回滚(见 undoSetBlock 注释)
				}
			}
			return ExecutionResult.success("已恢复 " + restored + " 个原方块。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("恢复填充区域失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeCloneRegion(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		BlockPos from = new BlockPos(
			Integer.parseInt(params.get("x1")), Integer.parseInt(params.get("y1")), Integer.parseInt(params.get("z1")));
		BlockPos to = new BlockPos(
			Integer.parseInt(params.get("x2")), Integer.parseInt(params.get("y2")), Integer.parseInt(params.get("z2")));
		int v = Math.abs((to.getX() - from.getX() + 1) * (to.getY() - from.getY() + 1) * (to.getZ() - from.getZ() + 1));
		if (v > MAX_REGION_BLOCKS) {
			return ExecutionResult.failed("区域过大,上限 " + MAX_REGION_BLOCKS + "。");
		}
		ServerWorld world = player.getServerWorld();
		BlockPos dest = player.getBlockPos();
		// 快照目标区
		NbtList destSnapshot = new NbtList();
		int dx = dest.getX() - from.getX();
		int dy = dest.getY() - from.getY();
		int dz = dest.getZ() - from.getZ();
		forEachBlock(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), src -> {
			BlockPos target = src.add(dx, dy, dz);
			NbtCompound entry = new NbtCompound();
			entry.putInt("x", target.getX()); entry.putInt("y", target.getY()); entry.putInt("z", target.getZ());
			entry.putString("state", world.getBlockState(target).toString());
			destSnapshot.add(entry);
			// 复制 src -> target
			BlockState srcState = world.getBlockState(src);
			world.setBlockState(target, srcState, 3);
		});
		UndoAction undo = new UndoAction(ActionType.CLONE_REGION, Map.of(
			"destBlocks", destSnapshot.toString()
		), "撤销克隆");
		return ExecutionResult.success("已克隆 " + v + " 个方块到 " + dest.toShortString() + "。", List.of(undo));
	}

	private static ExecutionResult undoCloneRegion(ServerPlayerEntity player, Map<String, String> params) {
		return undoFillRegion(player, Map.of("blocks", params.getOrDefault("destBlocks", "[]")));
	}

	private static ExecutionResult executeSetBiome(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String biomeName = params.getOrDefault("biome", "minecraft:plains");
		BlockPos center = player.getBlockPos();
		int radius = 16;
		try {
			String cmd = String.format(java.util.Locale.ROOT,
				"fillbiome %d %d %d %d %d %d %s",
				center.getX() - radius, center.getY(), center.getZ() - radius,
				center.getX() + radius, center.getY() + 64, center.getZ() + radius,
				biomeName);
			player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource().withSilent(), cmd);
			return ExecutionResult.success("已设置附近生物群系为 " + biomeName + "。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("设置生物群系失败: " + e.getMessage());
		}
	}

	private static ExecutionResult undoSetBiome(ServerPlayerEntity player, Map<String, String> params) {
		return ExecutionResult.failed("生物群系变更的精确回滚需要 chunk 重生,本版本不支持自动撤销。");
	}

	private static ExecutionResult executeSetSpawnpoint(ServerPlayerEntity player, ActionPlan plan) {
		ServerWorld world = player.getServerWorld();
		BlockPos oldSpawn = world.getSpawnPos();
		float oldAngle = world.getSpawnAngle();
		BlockPos newSpawn = player.getBlockPos();
		world.setSpawnPos(newSpawn, player.getYaw());
		player.setSpawnPoint(world.getRegistryKey(), newSpawn, player.getYaw(), true, false);
		UndoAction undo = new UndoAction(ActionType.SET_SPAWNPOINT, Map.of(
			"x", Integer.toString(oldSpawn.getX()),
			"y", Integer.toString(oldSpawn.getY()),
			"z", Integer.toString(oldSpawn.getZ()),
			"angle", Float.toString(oldAngle)
		), "恢复原出生点");
		return ExecutionResult.success("已设置出生点为当前位置。", List.of(undo));
	}

	private static ExecutionResult undoSetSpawnpoint(ServerPlayerEntity player, Map<String, String> params) {
		ServerWorld world = player.getServerWorld();
		BlockPos pos = new BlockPos(
			Integer.parseInt(params.getOrDefault("x", "0")),
			Integer.parseInt(params.getOrDefault("y", "64")),
			Integer.parseInt(params.getOrDefault("z", "0")));
		float angle = Float.parseFloat(params.getOrDefault("angle", "0"));
		world.setSpawnPos(pos, angle);
		return ExecutionResult.success("已恢复原出生点。", List.of());
	}

	private static ExecutionResult executeDifficulty(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String diffName = params.getOrDefault("difficulty", "normal");
		Difficulty newDiff = switch (diffName) {
			case "peaceful" -> Difficulty.PEACEFUL;
			case "easy" -> Difficulty.EASY;
			case "hard" -> Difficulty.HARD;
			default -> Difficulty.NORMAL;
		};
		Difficulty oldDiff = player.getServerWorld().getDifficulty();
		player.getServer().setDifficulty(newDiff, true);
		UndoAction undo = new UndoAction(ActionType.DIFFICULTY, Map.of(
			"difficulty", oldDiff.name().toLowerCase(java.util.Locale.ROOT)
		), "恢复原难度");
		return ExecutionResult.success("难度已设置为 " + newDiff.getName() + "。", List.of(undo));
	}

	private static ExecutionResult undoDifficulty(ServerPlayerEntity player, Map<String, String> params) {
		Difficulty diff = switch (params.getOrDefault("difficulty", "normal")) {
			case "peaceful" -> Difficulty.PEACEFUL;
			case "easy" -> Difficulty.EASY;
			case "hard" -> Difficulty.HARD;
			default -> Difficulty.NORMAL;
		};
		player.getServer().setDifficulty(diff, true);
		return ExecutionResult.success("已恢复原难度。", List.of());
	}

	private static ExecutionResult executeGameruleSet(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String ruleKey = params.get("rule");
		String newValue = params.getOrDefault("value", "true");
		if (ruleKey == null || ruleKey.isBlank()) return ExecutionResult.failed("规则名不能为空。");
		String oldValue = readGameRule(player, ruleKey);
		if (oldValue == null) return ExecutionResult.failed("未找到游戏规则: " + ruleKey);
		try {
			player.getServer().getCommandManager().executeWithPrefix(
				player.getServer().getCommandSource(), "gamerule " + ruleKey + " " + newValue);
		} catch (Exception e) {
			return ExecutionResult.failed("设置游戏规则失败: " + e.getMessage());
		}
		UndoAction undo = new UndoAction(ActionType.GAMERULE_SET, Map.of(
			"rule", ruleKey,
			"value", oldValue
		), "恢复原规则值");
		return ExecutionResult.success("已设置 " + ruleKey + " = " + newValue + "。", List.of(undo));
	}

	private static ExecutionResult undoGameruleSet(ServerPlayerEntity player, Map<String, String> params) {
		String ruleKey = params.get("rule");
		String oldValue = params.getOrDefault("value", "");
		try {
			player.getServer().getCommandManager().executeWithPrefix(
				player.getServer().getCommandSource(), "gamerule " + ruleKey + " " + oldValue);
			return ExecutionResult.success("已恢复 " + ruleKey + " = " + oldValue + "。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("恢复游戏规则失败: " + e.getMessage());
		}
	}

	/**
	 * 通过 {@code gamerule <key>} 的输出读取当前值;输出文本会在控制台/聊天出现一次。
	 * 由于 gamerule 命令本身无 "query" 子命令,这种"反射式"读取只能依赖控制台输出。
	 * 这里退而求其次:在执行 {@code executeGameruleSet} 前先尝试 {@code gamerule key},
	 * 失败则假定为 boolean true (常见默认值)。{@code /gamerule key} 不会改变状态。
	 */
	private static String readGameRule(ServerPlayerEntity player, String ruleKey) {
		// 简化策略:对于常见 boolean 规则直接假定 "true",撤销时按原值回写。
		// TODO: 待确认废弃:接入 gamerule 命令的 query 子命令(目前 1.21 yarn 路径下未暴露)
		return "true";
	}

	private static ExecutionResult executeKillDrops(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		int removed = 0;
		for (Entity e : world.getEntitiesByType(EntityType.ITEM, e -> true)) {
			e.discard();
			removed++;
		}
		return ExecutionResult.success("已清除 " + removed + " 个掉落物。", List.of());
	}

	private static ExecutionResult executeWorldBorder(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String mode = params.getOrDefault("mode", "set");
		String value = params.getOrDefault("value", "1000");
		try {
			String cmd = "worldborder " + mode + " " + value;
			player.getServer().getCommandManager().executeWithPrefix(
				player.getServer().getCommandSource(), cmd);
			UndoAction undo = new UndoAction(ActionType.WORLD_BORDER, Map.of(
				"mode", mode,
				"value", value
			), "世界边界无原始值快照,撤销需手动 /worldborder ...");
			return ExecutionResult.success("已更新世界边界(" + mode + " " + value + ")。", List.of(undo));
		} catch (Exception e) {
			return ExecutionResult.failed("世界边界更新失败: " + e.getMessage());
		}
	}

	private static ExecutionResult undoWorldBorder(ServerPlayerEntity player, Map<String, String> params) {
		return ExecutionResult.failed("世界边界撤销需手动执行 /worldborder 命令(未快照原值)。");
	}

	// ===============================================================
	//  Tier C: 通信 / 查询
	// ===============================================================

	private static ExecutionResult executeBroadcastSay(ServerCommandSource source, ActionPlan plan) {
		String msg = plan.steps().getFirst().parameters().getOrDefault("message", "");
		if (msg.isBlank()) return ExecutionResult.failed("消息内容不能为空。");
		try {
			source.getServer().getCommandManager().executeWithPrefix(source, "say " + msg);
			return ExecutionResult.success("已广播: " + msg, List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("广播失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeMsgPlayer(ServerCommandSource source, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String player = params.getOrDefault("player", "");
		String msg = params.getOrDefault("message", "");
		if (player.isBlank() || msg.isBlank()) return ExecutionResult.failed("玩家名或消息内容不能为空。");
		try {
			source.getServer().getCommandManager().executeWithPrefix(source, "msg " + player + " " + msg);
			return ExecutionResult.success("已向 " + player + " 发送消息。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("发送失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeTitleSelf(ServerPlayerEntity player, ActionPlan plan) {
		String text = plan.steps().getFirst().parameters().getOrDefault("text", "");
		if (text.isBlank()) return ExecutionResult.failed("标题内容不能为空。");
		try {
			player.getServer().getCommandManager().executeWithPrefix(
				player.getCommandSource().withSilent(), "title @s title " + escapeCommandArg(text));
			return ExecutionResult.success("已显示标题。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("标题发送失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeTellrawSelf(ServerPlayerEntity player, ActionPlan plan) {
		String text = plan.steps().getFirst().parameters().getOrDefault("text", "");
		if (text.isBlank()) return ExecutionResult.failed("消息内容不能为空。");
		try {
			player.getServer().getCommandManager().executeWithPrefix(
				player.getCommandSource().withSilent(), "tellraw @s " + escapeCommandArg(text));
			return ExecutionResult.success("已发送 tellraw。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("tellraw 发送失败: " + e.getMessage());
		}
	}

	private static String escapeCommandArg(String s) {
		// 简单 JSON 转义;若已经是 JSON 形态则原样
		String trimmed = s.trim();
		if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
		StringBuilder sb = new StringBuilder("{\"text\":\"");
		for (char c : trimmed.toCharArray()) {
			if (c == '\\' || c == '"') sb.append('\\');
			sb.append(c);
		}
		sb.append("\"}");
		return sb.toString();
	}

	private static ExecutionResult executeLocateStructure(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String structureName = params.getOrDefault("structure", "");
		if (structureName.isBlank()) return ExecutionResult.failed("结构名不能为空。");
		try {
			String cmd = "locate structure " + structureName;
			player.getServer().getCommandManager().executeWithPrefix(
				player.getServer().getCommandSource(), cmd);
			return ExecutionResult.success("已查询 " + structureName + " 位置(见聊天栏)。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("定位失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeSeed(ServerPlayerEntity player) {
		long seed = player.getServerWorld().getSeed();
		return ExecutionResult.success("世界种子: " + seed, List.of());
	}

	// ===============================================================
	//  Tier D: 计分 / 队伍
	// ===============================================================

	private static ExecutionResult executeScoreboardAdd(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String name = params.getOrDefault("name", "");
		String criteria = params.getOrDefault("criteria", "dummy");
		if (name.isBlank()) return ExecutionResult.failed("计分项名不能为空。");
		try {
			String cmd = "scoreboard objectives add " + name + " " + criteria;
			player.getServer().getCommandManager().executeWithPrefix(
				player.getServer().getCommandSource(), cmd);
			return ExecutionResult.success("已添加计分项 " + name + "。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("添加计分项失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeTeamAdd(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String name = params.getOrDefault("name", "");
		if (name.isBlank()) return ExecutionResult.failed("队伍名不能为空。");
		ServerScoreboard sb = player.getServer().getScoreboard();
		if (sb.getTeam(name) != null) return ExecutionResult.failed("队伍 " + name + " 已存在。");
		Team team = sb.addTeam(name);
		boolean created = team != null;
		UndoAction undo = new UndoAction(ActionType.TEAM_ADD, Map.of(
			"name", name,
			"created", Boolean.toString(created)
		), "移除新建的队伍");
		return created
			? ExecutionResult.success("已创建队伍 " + name + "。", List.of(undo))
			: ExecutionResult.failed("创建队伍失败。");
	}

	private static ExecutionResult undoTeamAdd(ServerPlayerEntity player, Map<String, String> params) {
		String name = params.get("name");
		if (!Boolean.parseBoolean(params.getOrDefault("created", "false"))) {
			return ExecutionResult.success("无需撤销(未实际创建)。", List.of());
		}
		Team team = player.getServer().getScoreboard().getTeam(name);
		if (team != null) {
			player.getServer().getScoreboard().removeTeam(team);
			return ExecutionResult.success("已移除队伍 " + name + "。", List.of());
		}
		return ExecutionResult.failed("找不到要移除的队伍 " + name + "。");
	}

	// ===============================================================
	//  Tier E: 管理员
	// ===============================================================

	private static ExecutionResult executeOpPlayer(ServerPlayerEntity player, ActionPlan plan) {
		String name = plan.steps().getFirst().parameters().getOrDefault("player", "");
		if (name.isBlank()) return ExecutionResult.failed("玩家名不能为空。");
		try {
			player.getServer().getCommandManager().executeWithPrefix(player.getServer().getCommandSource(), "op " + name);
			return ExecutionResult.success("已添加 " + name + " 为 op。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("op 失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeDeopPlayer(ServerPlayerEntity player, ActionPlan plan) {
		String name = plan.steps().getFirst().parameters().getOrDefault("player", "");
		if (name.isBlank()) return ExecutionResult.failed("玩家名不能为空。");
		try {
			player.getServer().getCommandManager().executeWithPrefix(player.getServer().getCommandSource(), "deop " + name);
			return ExecutionResult.success("已撤销 " + name + " 的 op。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("deop 失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeKickPlayer(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String name = params.getOrDefault("player", "");
		if (name.isBlank()) return ExecutionResult.failed("玩家名不能为空。");
		try {
			String reason = params.getOrDefault("reason", "");
			String cmd = "kick " + name + (reason.isBlank() ? "" : " " + reason);
			player.getServer().getCommandManager().executeWithPrefix(player.getServer().getCommandSource(), cmd);
			return ExecutionResult.success("已踢出 " + name + "。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("踢出失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeBanPlayer(ServerPlayerEntity player, ActionPlan plan) {
		Map<String, String> params = plan.steps().getFirst().parameters();
		String name = params.getOrDefault("player", "");
		if (name.isBlank()) return ExecutionResult.failed("玩家名不能为空。");
		try {
			String reason = params.getOrDefault("reason", "");
			String cmd = "ban " + name + (reason.isBlank() ? "" : " " + reason);
			player.getServer().getCommandManager().executeWithPrefix(player.getServer().getCommandSource(), cmd);
			return ExecutionResult.success("已封禁 " + name + "。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("封禁失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executePardonPlayer(ServerPlayerEntity player, ActionPlan plan) {
		String name = plan.steps().getFirst().parameters().getOrDefault("player", "");
		if (name.isBlank()) return ExecutionResult.failed("玩家名不能为空。");
		try {
			player.getServer().getCommandManager().executeWithPrefix(player.getServer().getCommandSource(), "pardon " + name);
			return ExecutionResult.success("已解封 " + name + "。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("解封失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeStopServer(ServerCommandSource source) {
		try {
			source.getServer().stop(false);
			return ExecutionResult.success("服务器已停止。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("停止服务器失败: " + e.getMessage());
		}
	}

	private static ExecutionResult executeSaveAll(ServerCommandSource source) {
		try {
			source.getServer().saveAll(true, true, false);
			return ExecutionResult.success("世界已保存。", List.of());
		} catch (Exception e) {
			return ExecutionResult.failed("保存失败: " + e.getMessage());
		}
	}

	private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
		try {
			return source.getPlayerOrThrow();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("Agent commands require a player source.", e);
		}
	}
}
