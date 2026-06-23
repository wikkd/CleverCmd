package com.example.agentcli.agent;

import java.util.List;

/**
 * 当前 agent 支持的全部 {@link ActionType} 目录(单一来源)。
 *
 * <p>供 NLU 后端生成系统 prompt、UI 展示 / 帮助、admin 启用前后差异。
 * 顺序按 Tier 分组(控制 / 玩家自身 / 查询 / 世界 / 通信 / 计分 / 管理员 / 杂项 / 兜底),
 * 方便人工阅读。</p>
 */
public final class AgentActionCatalog {
	private final List<ActionType> supportedActions = List.of(
		// 控制类
		ActionType.HELP,
		ActionType.STATUS,
		ActionType.UNDO,
		ActionType.CANCEL,
		// 玩家自身
		ActionType.TELEPORT_SELF,
		ActionType.TELEPORT_OTHER,
		ActionType.GIVE_ITEM,
		ActionType.SET_GAMEMODE,
		ActionType.GIVE_EFFECT,
		ActionType.EFFECT_CLEAR,
		ActionType.ENCHANT_ITEM,
		ActionType.HEAL,
		ActionType.SET_HEALTH,
		ActionType.DAMAGE_SELF,
		ActionType.GIVE_XP,
		ActionType.KILL_SELF,
		ActionType.RESPAWN_SELF,
		ActionType.RIDE_DISMOUNT,
		ActionType.SPECTATE,
		ActionType.TAG_ADD,
		ActionType.TAG_REMOVE,
		ActionType.TITLE_SELF,
		ActionType.TELLRAW_SELF,
		// 查询
		ActionType.LOCATE_STRUCTURE,
		ActionType.SEED,
		// 世界
		ActionType.SET_TIME,
		ActionType.SET_WEATHER,
		ActionType.SET_SPAWNPOINT,
		ActionType.DIFFICULTY,
		ActionType.GAMERULE_SET,
		ActionType.WORLD_BORDER,
		ActionType.SPAWN_ENTITY,
		ActionType.KILL_DROPS,
		ActionType.SET_BLOCK,
		ActionType.FILL_REGION,
		ActionType.CLONE_REGION,
		ActionType.SET_BIOME,
		// 通信
		ActionType.BROADCAST_SAY,
		ActionType.MSG_PLAYER,
		// 计分/队伍(默认黑名单)
		ActionType.SCOREBOARD_ADD,
		ActionType.TEAM_ADD,
		// 管理员(默认黑名单,需 allowAdminCommands=true)
		ActionType.OP_PLAYER,
		ActionType.DEOP_PLAYER,
		ActionType.KICK_PLAYER,
		ActionType.BAN_PLAYER,
		ActionType.PARDON_PLAYER,
		ActionType.STOP_SERVER,
		ActionType.SAVE_ALL,
		// 杂项
		ActionType.CLEAR_INVENTORY,
		// 兜底
		ActionType.RUN_COMMAND
	);

	public List<ActionType> supportedActions() {
		return supportedActions;
	}

	public String helpText() {
		boolean adminEnabled = com.example.agentcli.client.config.AiConfigStore.get().allowAdminCommands();
		String adminSection = adminEnabled ? """

			管理员指令(已启用):
			- op/deop <玩家>、ban/pardon <玩家>、kick <玩家>
			- stop(关闭服务器)、save-all
			""" : """
			管理员指令:已禁用,需在 Cloth Config 中开启「允许管理员指令」。
			""";
		return """
			支持的意图(可用自由语言,无需模板):

			控制:
			- 帮助 / help / 能做什么
			- 状态 / 位置 / 我在哪
			- 撤销 / undo
			- 取消 / cancel

			玩家自身:
			- 传送(自己到坐标/出生点/原点,或把别人传送)
			- 给予物品(如:给我钻石、来点吃的)
			- 切换模式(切创造/生存/冒险/旁观)
			- 药水效果(给/清)、附魔(手持物品)、治疗、回血/回饥饿
			- 经验(给我 10 级经验)、自杀、重生、下坐骑、旁观
			- 标签(给自己加 glowing 标签)

			世界:
			- 时间(白天/正午/夜晚/午夜)、天气(晴/雨/雷)
			- 出生点(设置)、难度(和平/简单/普通/困难)
			- 规则(gamerule,如 keepInventory)
			- 方块(在脚下放钻石块、填充一个区域、克隆区块)
			- 生物群系、边界、生成实体、清除掉落物

			通信:
			- 广播(对全服说)、私聊(对 Alice 说)
			- 标题/原始文本(给自己发一条)

			查询:
			- 定位结构(最近村庄在哪)、种子
			""" + adminSection + """

			所有 AI 执行动作都会先等待确认:/agent confirm 执行,/agent cancel 取消。
			""";
	}
}