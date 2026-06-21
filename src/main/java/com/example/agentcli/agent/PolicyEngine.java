package com.example.agentcli.agent;

import net.minecraft.server.command.ServerCommandSource;

public final class PolicyEngine {
	public PolicyDecision evaluate(ServerCommandSource source, ActionPlan plan) {
		ActionType type = plan.actionType();
		boolean selfScope = switch (type) {
			case HELP, STATUS, UNDO, CANCEL, TELEPORT_SELF, GIVE_ITEM, SET_GAMEMODE -> true;
			case SET_TIME -> false;
		};

		if (type == ActionType.CANCEL || type == ActionType.UNDO || type == ActionType.HELP || type == ActionType.STATUS) {
			return new PolicyDecision(true, false, "只读或控制指令", plan.riskLevel(), selfScope ? PermissionScope.SELF : PermissionScope.WORLD);
		}

		if (type == ActionType.SET_TIME && !source.hasPermissionLevel(2) && !source.getServer().isSingleplayer()) {
			return new PolicyDecision(false, false, "调整世界时间需要更高权限", plan.riskLevel(), PermissionScope.WORLD);
		}

		if (plan.requiresConfirmation()) {
			return new PolicyDecision(true, true, "该操作需要玩家确认", plan.riskLevel(), selfScope ? PermissionScope.SELF : PermissionScope.WORLD);
		}
		return new PolicyDecision(true, false, "允许执行", plan.riskLevel(), selfScope ? PermissionScope.SELF : PermissionScope.WORLD);
	}
}

