package com.example.agentcli.agent;

import java.util.List;

public final class AgentActionCatalog {
	private final List<ActionType> supportedActions = List.of(
		ActionType.HELP,
		ActionType.STATUS,
		ActionType.TELEPORT_SELF,
		ActionType.GIVE_ITEM,
		ActionType.SET_TIME,
		ActionType.SET_GAMEMODE,
		ActionType.UNDO,
		ActionType.CANCEL
	);

	public List<ActionType> supportedActions() {
		return supportedActions;
	}

	public String helpText() {
		return """
			支持的意图：
			- 帮助 / help
			- 状态 / 位置 / 我在哪
			- 传送到 <x y z> / 传送到出生点 / 原点
			- 给我 <数量> <物品>
			- 把时间改成 白天 / 夜晚 / 中午 / 午夜
			- 切换到 生存 / 创造 / 冒险 / 旁观
			- 撤销 / undo
			- 取消 / cancel
			""";
	}
}

