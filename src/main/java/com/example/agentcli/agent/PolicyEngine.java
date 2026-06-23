package com.example.agentcli.agent;

import com.example.agentcli.client.config.AiConfigStore;
import net.minecraft.server.command.ServerCommandSource;

/**
 * 把 {@link ActionPlan} 转成 {@link PolicyDecision} 的策略闸门。
 *
 * <p>两阶段检查:
 * <ol>
 *   <li>控制类指令(HELP/STATUS/UNDO/CANCEL)免检放行</li>
 *   <li>黑名单 → 拒绝;RUN_COMMAND 走 {@code MinecraftCommandSupport.validate};
 *       管理员指令额外要求 {@code AiConfigStore.allowAdminCommands} +
 *       permissionLevel >= 4;世界级动作要求 permissionLevel >= 2</li>
 * </ol>
 *
 * <p>策略由数据驱动(读取 {@code AiConfigStore}),UI 端保存后通过
 * {@code /agent reload} 立即生效,无需重启世界。</p>
 */
public final class PolicyEngine {
	/**
	 * 一次性枚举所有需要世界级权限(permissionLevel>=2 或单机)的动作。
	 * RUN_COMMAND 走 MinecraftCommandSupport 的解析闸门,不依赖此处。
	 */
	private static final ActionType[] WORLD_PERMISSION_ACTIONS = {
		// 原有
		ActionType.SET_TIME, ActionType.SET_WEATHER, ActionType.SPAWN_ENTITY,
		// Tier B
		ActionType.SET_BLOCK, ActionType.FILL_REGION, ActionType.CLONE_REGION,
		ActionType.SET_BIOME, ActionType.SET_SPAWNPOINT, ActionType.DIFFICULTY,
		ActionType.GAMERULE_SET, ActionType.KILL_DROPS, ActionType.WORLD_BORDER,
		// Tier C/D 通信与计分
		ActionType.BROADCAST_SAY, ActionType.MSG_PLAYER,
		ActionType.SCOREBOARD_ADD, ActionType.TEAM_ADD
	};

	/**
	 * 管理员指令(Tier E):除 allowAdminCommands=true 外,还需 permissionLevel>=4 或单机。
	 */
	private static final ActionType[] ADMIN_ACTIONS = {
		ActionType.OP_PLAYER, ActionType.DEOP_PLAYER,
		ActionType.KICK_PLAYER, ActionType.BAN_PLAYER, ActionType.PARDON_PLAYER,
		ActionType.STOP_SERVER, ActionType.SAVE_ALL
	};

	public PolicyDecision evaluate(ServerCommandSource source, ActionPlan plan) {
		ActionType type = plan.actionType();
		boolean selfScope = switch (type) {
			// 控制类
			case HELP, STATUS, UNDO, CANCEL -> true;
			// Tier A:玩家自身
			case TELEPORT_SELF, TELEPORT_OTHER, GIVE_ITEM, SET_GAMEMODE,
				GIVE_EFFECT, EFFECT_CLEAR, ENCHANT_ITEM, HEAL, SET_HEALTH,
				DAMAGE_SELF, GIVE_XP, KILL_SELF, RESPAWN_SELF, RIDE_DISMOUNT,
				SPECTATE, TAG_ADD, TAG_REMOVE, TITLE_SELF, TELLRAW_SELF -> true;
			// 查询类(只读)
			case LOCATE_STRUCTURE, SEED -> true;
			// 世界级
			case SET_TIME, SET_WEATHER, SET_BLOCK, FILL_REGION, CLONE_REGION,
				SET_BIOME, SET_SPAWNPOINT, DIFFICULTY, GAMERULE_SET, WORLD_BORDER,
				SPAWN_ENTITY, KILL_DROPS, BROADCAST_SAY, MSG_PLAYER,
				SCOREBOARD_ADD, TEAM_ADD,
				// Tier E 管理员(尽管作用于他人,但仍在 WORLD scope)
				OP_PLAYER, DEOP_PLAYER, KICK_PLAYER, BAN_PLAYER, PARDON_PLAYER,
				STOP_SERVER, SAVE_ALL,
				// 兜底
				RUN_COMMAND, CLEAR_INVENTORY -> false;
		};

		// 控制指令不受黑名单影响
		if (type == ActionType.CANCEL || type == ActionType.UNDO || type == ActionType.HELP || type == ActionType.STATUS) {
			return new PolicyDecision(true, false, "只读或控制指令", plan.riskLevel(), selfScope ? PermissionScope.SELF : PermissionScope.WORLD);
		}

		// 黑名单检查
		if (AiConfigStore.get().isBlacklisted(type.name())) {
			return new PolicyDecision(false, false, "该指令已被加入黑名单", plan.riskLevel(), selfScope ? PermissionScope.SELF : PermissionScope.WORLD);
		}

		if (type == ActionType.RUN_COMMAND) {
			String command = plan.steps().getFirst().parameters().get(MinecraftCommandSupport.COMMAND_PARAM);
			MinecraftCommandSupport.CommandValidationResult validation = MinecraftCommandSupport.validate(source, command);
			if (!validation.valid()) {
				return new PolicyDecision(false, false, validation.reason(), plan.riskLevel(), PermissionScope.WORLD);
			}
			return new PolicyDecision(true, false, "允许执行 /" + validation.rootCommand(), plan.riskLevel(), PermissionScope.WORLD);
		}

		// Tier E 管理员指令闸门:先查 allowAdminCommands,再查权限级
		if (isAdminAction(type)) {
			if (!AiConfigStore.get().allowAdminCommands()) {
				return new PolicyDecision(false, false, "管理员指令已被禁用,需在 Cloth Config 中启用「允许管理员指令」", plan.riskLevel(), PermissionScope.WORLD);
			}
			if (!source.hasPermissionLevel(4) && !source.getServer().isSingleplayer()) {
				return new PolicyDecision(false, false, "管理员指令需要 permissionLevel>=4", plan.riskLevel(), PermissionScope.WORLD);
			}
		}

		// 世界级动作的权限级闸门
		if (isWorldPermissionAction(type) && !source.hasPermissionLevel(2) && !source.getServer().isSingleplayer()) {
			return new PolicyDecision(false, false, "该指令需要更高权限(permissionLevel>=2)", plan.riskLevel(), PermissionScope.WORLD);
		}

		return new PolicyDecision(true, false, "允许执行", plan.riskLevel(), selfScope ? PermissionScope.SELF : PermissionScope.WORLD);
	}

	private static boolean isWorldPermissionAction(ActionType type) {
		for (ActionType t : WORLD_PERMISSION_ACTIONS) {
			if (t == type) {
				return true;
			}
		}
		return false;
	}

	private static boolean isAdminAction(ActionType type) {
		for (ActionType t : ADMIN_ACTIONS) {
			if (t == type) {
				return true;
			}
		}
		return false;
	}
}