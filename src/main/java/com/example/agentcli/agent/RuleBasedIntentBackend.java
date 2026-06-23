package com.example.agentcli.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则解析后端:不依赖 LLM,把自然语言映射到 {@link IntentCandidate}。
 *
 * <p>输入归一化后,按 Tier A → C → B → D → E 的顺序触发专用 {@code parseX} 方法;
 * 全部返回 null 的方法不写入候选,候选列表最终供 {@code AgentService} 选最高 confidence。</p>
 *
 * <p>中文别名集中在 {@link AliasTables};新增/删除别名时同步修改模型 prompt。</p>
 */
public final class RuleBasedIntentBackend implements IntentBackend {
	// 通用坐标 / 区域
	private static final Pattern COORDINATES = Pattern.compile("(-?\\d+)\\D+(-?\\d+)\\D+(-?\\d+)");
	private static final Pattern RELATIVE_COORDS = Pattern.compile("~(-?\\d+)?\\D+~(-?\\d+)?\\D+~(-?\\d+)?");
	private static final Pattern REGION_PATTERN = Pattern.compile("(-?\\d+)\\D+(-?\\d+)\\D+(-?\\d+)\\D+(-?\\d+)\\D+(-?\\d+)\\D+(-?\\d+)");
	// 经验 / 血量 / 伤害
	private static final Pattern XP_PATTERN = Pattern.compile("(\\d+)\\s*(?:级|levels?|点|points?)");
	private static final Pattern HEALTH_PATTERN = Pattern.compile("(?:(?:血|血量|health|hp)\\s*为?\\s*|设为?\\s*(?:血|血量|health|hp)\\s*)(\\d+)|(\\d+)\\s*(?:血|血量|health|hp|点生命)");
	private static final Pattern DAMAGE_PATTERN = Pattern.compile("(\\d+)\\s*(?:点伤害|伤害|damage)");
	// 物品 / 命令
	private static final Pattern GIVE_PATTERN = Pattern.compile("(?:给我|给予|give\\s+me|give)\\s*(\\d+)?\\s*(?:个|份|x|X)?\\s*([\\p{IsHan}A-Za-z0-9:_-]+)");
	private static final Pattern RUN_COMMAND_PATTERN = Pattern.compile("^(?:执行|运行|run|execute|command|命令)\\s*[:：]?\\s*/?(.+)$");

	@Override
	public String name() {
		return "rule";
	}

	@Override
	public IntentParseResult parse(String rawText, AgentContext context, AgentActionCatalog catalog) {
		String normalized = normalize(rawText);
		List<IntentCandidate> candidates = new ArrayList<>();

		// 命令透传优先(若用户明确说"执行 /xxx",直接走 RUN_COMMAND,不再考虑其他动作)
		addIfNotNull(candidates, parseRunCommand(rawText));

		// 控制类指令 — 优先级最高,匹配到一个就足以解读输入
		if (isHelp(normalized)) {
			candidates.add(candidate(ActionType.HELP, 1.0, "显示支持的意图", Map.of(), RiskLevel.READ_ONLY, false));
		}
		if (isStatus(normalized)) {
			candidates.add(candidate(ActionType.STATUS, 1.0, "查看当前状态", Map.of(), RiskLevel.READ_ONLY, false));
		}
		if (isUndo(normalized)) {
			candidates.add(candidate(ActionType.UNDO, 1.0, "撤销最近一次可撤销动作", Map.of(), RiskLevel.READ_ONLY, false));
		}
		if (isCancel(normalized)) {
			candidates.add(candidate(ActionType.CANCEL, 1.0, "取消当前待确认计划", Map.of(), RiskLevel.READ_ONLY, false));
		}

		// Tier A:玩家自身
		addIfNotNull(candidates, parseTeleportSelf(normalized));
		addIfNotNull(candidates, parseTeleportOtherInternal(normalized, rawText));
		addIfNotNull(candidates, parseGive(normalized));
		addIfNotNull(candidates, parseGameMode(normalized));
		addIfNotNull(candidates, parseGiveEffect(normalized));
		addIfNotNull(candidates, parseEffectClear(normalized));
		addIfNotNull(candidates, parseEnchantItem(normalized));
		addIfNotNull(candidates, parseHeal(normalized));
		addIfNotNull(candidates, parseSetHealth(normalized));
		addIfNotNull(candidates, parseDamageSelf(normalized));
		addIfNotNull(candidates, parseGiveXp(normalized));
		addIfNotNull(candidates, parseKillSelf(normalized));
		addIfNotNull(candidates, parseRespawnSelf(normalized));
		addIfNotNull(candidates, parseRideDismount(normalized));
		addIfNotNull(candidates, parseSpectate(normalized));
		addIfNotNull(candidates, parseClearInventory(normalized));
		addIfNotNull(candidates, parseTagAdd(normalized));
		addIfNotNull(candidates, parseTagRemove(normalized));

		// Tier C:查询/通信
		addIfNotNull(candidates, parseTitleSelf(rawText));
		addIfNotNull(candidates, parseTellrawSelf(rawText));
		addIfNotNull(candidates, parseLocateStructure(normalized));
		addIfNotNull(candidates, parseSeed(normalized));

		// Tier B:世界/方块
		addIfNotNull(candidates, parseTime(normalized));
		addIfNotNull(candidates, parseWeather(normalized));
		addIfNotNull(candidates, parseSpawnEntity(normalized));
		addIfNotNull(candidates, parseKillDrops(normalized));
		addIfNotNull(candidates, parseSetBlock(rawText, normalized));
		addIfNotNull(candidates, parseFillRegion(rawText, normalized));
		addIfNotNull(candidates, parseCloneRegion(normalized));
		addIfNotNull(candidates, parseSetBiome(rawText, normalized));
		addIfNotNull(candidates, parseSetSpawnpoint(normalized));
		addIfNotNull(candidates, parseDifficulty(normalized));
		addIfNotNull(candidates, parseGameruleSet(rawText, normalized));
		addIfNotNull(candidates, parseWorldBorder(rawText, normalized));

		// Tier C 通信
		addIfNotNull(candidates, parseBroadcastSay(rawText));
		addIfNotNull(candidates, parseMsgPlayer(rawText));

		// Tier D 计分/队伍
		addIfNotNull(candidates, parseScoreboardAdd(rawText));
		addIfNotNull(candidates, parseTeamAdd(rawText));

		// Tier E 管理员
		addIfNotNull(candidates, parseOpPlayer(rawText));
		addIfNotNull(candidates, parseDeopPlayer(rawText));
		addIfNotNull(candidates, parseKickPlayer(rawText));
		addIfNotNull(candidates, parseBanPlayer(rawText));
		addIfNotNull(candidates, parsePardonPlayer(rawText));
		addIfNotNull(candidates, parseStopServer(rawText));
		addIfNotNull(candidates, parseSaveAll(rawText));

		if (candidates.isEmpty()) {
			return IntentParseResult.none(normalized, "未识别到明确意图。你可以输入“帮助”查看支持的操作。");
		}
		return new IntentParseResult(normalized, candidates, false, null);
	}

	// ============================================================
	//  控制类短路
	// ============================================================

	private static boolean isHelp(String n) {
		return containsAny(n, "帮助", "help", "?", "能做什么", "支持什么");
	}

	private static boolean isStatus(String n) {
		return containsAny(n, "状态", "位置", "我在哪", "当前状态", "我的位置");
	}

	private static boolean isUndo(String n) {
		return containsAny(n, "撤销", "undo", "回退", "恢复上一步");
	}

	private static boolean isCancel(String n) {
		return containsAny(n, "取消", "cancel", "停止当前", "终止");
	}

	// ============================================================
	//  Tier A 玩家自身
	// ============================================================

	private static IntentCandidate parseTeleportSelf(String n) {
		if (!containsAny(n, "传送", "tp", "teleport")) {
			return null;
		}
		Map<String, String> params = new HashMap<>();
		if (containsAny(n, "出生点", "出生", "spawn")) {
			params.put("target", "spawn");
			return candidate(ActionType.TELEPORT_SELF, 0.95, "传送到出生点", params, RiskLevel.MEDIUM, true);
		}
		if (containsAny(n, "原点", "world origin")) {
			params.put("target", "origin");
			return candidate(ActionType.TELEPORT_SELF, 0.9, "传送到原点", params, RiskLevel.MEDIUM, true);
		}
		Matcher relative = RELATIVE_COORDS.matcher(n);
		if (relative.find()) {
			params.put("target", "coordinates");
			params.put("x", nullSafeGroup(relative, 1, "0"));
			params.put("y", nullSafeGroup(relative, 2, "0"));
			params.put("z", nullSafeGroup(relative, 3, "0"));
			params.put("relative", "true");
			return candidate(ActionType.TELEPORT_SELF, 0.95, "相对坐标传送", params, RiskLevel.MEDIUM, true);
		}
		Matcher m = COORDINATES.matcher(n);
		if (m.find()) {
			params.put("target", "coordinates");
			params.put("x", m.group(1));
			params.put("y", m.group(2));
			params.put("z", m.group(3));
			return candidate(ActionType.TELEPORT_SELF, 0.92, "按坐标传送", params, RiskLevel.MEDIUM, true);
		}
		return null;
	}

	/**
	 * @param normalized 归一化文本(用于触发词和坐标匹配)
	 * @param source     原始文本(用于提取玩家名,保留原始大小写)
	 */
	private static IntentCandidate parseTeleportOtherInternal(String normalized, String source) {
		if (!containsAny(normalized, "传送", "tp", "teleport") || !containsAny(normalized, "把", "将")) {
			return null;
		}
		// 玩家名从原始大小写提取,避免被 normalize() 小写化
		Matcher m = Pattern.compile("(?:把|将)\\s*([A-Za-z0-9_]{2,16})").matcher(source);
		if (!m.find()) {
			return null;
		}
		String target = m.group(1);
		Map<String, String> params = new HashMap<>();
		params.put("target", target);
		if (containsAny(normalized, "我这里", "我这", "me", "我")) {
			params.put("destination", "self");
			return candidate(ActionType.TELEPORT_OTHER, 0.9, "把 " + target + " 传送到我这里", params, RiskLevel.MEDIUM, true);
		}
		Matcher coords = COORDINATES.matcher(normalized);
		if (coords.find()) {
			params.put("destination", "coordinates");
			params.put("x", coords.group(1));
			params.put("y", coords.group(2));
			params.put("z", coords.group(3));
			return candidate(ActionType.TELEPORT_OTHER, 0.85, "把 " + target + " 传送到坐标", params, RiskLevel.MEDIUM, true);
		}
		return null;
	}

	private static IntentCandidate parseGive(String n) {
		Matcher m = GIVE_PATTERN.matcher(n);
		if (!m.find()) {
			return null;
		}
		String count = m.group(1) == null ? "1" : m.group(1);
		String itemToken = m.group(2);
		// 排除非物品令牌:经验/级/伤害 等不应被误判为物品
		if (containsAny(itemToken, "经验", "级", "伤害", "血量", "饥饿", "附魔", "效果")) {
			return null;
		}
		String itemId = normalizeItem(itemToken);
		return candidate(ActionType.GIVE_ITEM, 0.95, "给予物品", Map.of("item", itemId, "count", count), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseGameMode(String n) {
		if (!containsAny(n, "模式", "切换", "生存", "创造", "冒险", "旁观", "gamemode")) {
			return null;
		}
		String mode = null;
		if (containsAny(n, "创造", "creative")) mode = "creative";
		else if (containsAny(n, "生存", "survival")) mode = "survival";
		else if (containsAny(n, "冒险", "adventure")) mode = "adventure";
		else if (containsAny(n, "旁观", "spectator")) mode = "spectator";
		if (mode == null) return null;
		return candidate(ActionType.SET_GAMEMODE, 0.9, "切换游戏模式", Map.of("mode", mode), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseGiveEffect(String n) {
		if (!containsAny(n, "效果", "药水", "effect", "potion", "速度", "力量", "再生", "夜视", "隐身", "跳跃", "防火", "水下呼吸")) {
			return null;
		}
		String effect = null;
		if (containsAny(n, "速度", "speed")) effect = "speed";
		else if (containsAny(n, "力量", "strength")) effect = "strength";
		else if (containsAny(n, "再生", "regeneration", "regen")) effect = "regeneration";
		else if (containsAny(n, "防火", "fire_resistance", "fire resistance")) effect = "fire_resistance";
		else if (containsAny(n, "夜视", "night_vision", "night vision")) effect = "night_vision";
		else if (containsAny(n, "隐身", "invisible", "invisibility")) effect = "invisibility";
		else if (containsAny(n, "跳跃", "jump")) effect = "jump_boost";
		else if (containsAny(n, "水下呼吸", "water_breathing", "water breathing")) effect = "water_breathing";
		else if (containsAny(n, "生命提升", "health_boost")) effect = "health_boost";
		else if (containsAny(n, "伤害吸收", "absorption")) effect = "absorption";
		else if (containsAny(n, "急迫", "haste")) effect = "haste";
		else if (containsAny(n, "抗性", "resistance")) effect = "resistance";
		if (effect == null) return null;
		Map<String, String> params = new HashMap<>();
		params.put("effect", effect);
		Matcher dur = Pattern.compile("(\\d+)\\s*(?:秒|s|second)").matcher(n);
		if (dur.find()) params.put("duration", dur.group(1));
		Matcher amp = Pattern.compile("(?:等级|level|amplifier)\\s*(\\d+)").matcher(n);
		if (amp.find()) params.put("amplifier", amp.group(1));
		return candidate(ActionType.GIVE_EFFECT, 0.9, "给予" + effect + "效果", params, RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseEffectClear(String n) {
		if (!containsAny(n, "清除效果", "清效果", "清药水", "清状态", "effect clear", "清除药水")) {
			return null;
		}
		return candidate(ActionType.EFFECT_CLEAR, 0.9, "清除全部药水效果", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseEnchantItem(String n) {
		if (!containsAny(n, "附魔", "enchant")) {
			return null;
		}
		String enchant = null;
		for (Map.Entry<String, String> entry : AliasTables.ENCHANT_ALIASES.entrySet()) {
			if (n.contains(entry.getKey())) {
				enchant = entry.getValue();
				break;
			}
		}
		if (enchant == null) return null;
		Map<String, String> params = new HashMap<>();
		params.put("enchantment", enchant);
		Matcher lvl = Pattern.compile("(?:等级|level)\\s*(\\d+)").matcher(n);
		if (lvl.find()) params.put("level", lvl.group(1));
		return candidate(ActionType.ENCHANT_ITEM, 0.85, "附魔 " + enchant, params, RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseHeal(String n) {
		if (!containsAny(n, "治疗", "回血", "恢复血量", "回满血", "恢复饥饿", "回满饥饿", "heal", "回复")) {
			return null;
		}
		String target = "all";
		if (containsAny(n, "回血", "恢复血量", "回满血", "恢复血")) target = "health";
		else if (containsAny(n, "恢复饥饿", "回满饥饿", "饥饿")) target = "hunger";
		return candidate(ActionType.HEAL, 0.95, "治疗(" + target + ")", Map.of("target", target), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseSetHealth(String n) {
		if (!containsAny(n, "设置血量", "设置生命", "设置最大血量", "调整血量", "set health", "set health to")) {
			return null;
		}
		Matcher m = HEALTH_PATTERN.matcher(n);
		if (!m.find()) return null;
		String health = m.group(1) != null ? m.group(1) : m.group(2);
		return candidate(ActionType.SET_HEALTH, 0.85, "设置血量为 " + health,
			Map.of("health", health), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseDamageSelf(String n) {
		if (!containsAny(n, "自伤", "伤害自己", "掉血", "受到伤害", "点伤害", "伤害", "damage me", "hurt me")) {
			return null;
		}
		Matcher m = DAMAGE_PATTERN.matcher(n);
		String amount = m.find() ? m.group(1) : "1";
		return candidate(ActionType.DAMAGE_SELF, 0.85, "对自己造成 " + amount + " 点伤害",
			Map.of("amount", amount), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseGiveXp(String n) {
		if (!containsAny(n, "经验", "xp", "experience", "等级") || containsAny(n, "清除效果", "清效果")) {
			return null;
		}
		if (containsAny(n, "清除经验", "清空经验", "清经验", "重置经验", "xp set 0")) {
			return candidate(ActionType.GIVE_XP, 0.85, "清空经验", Map.of("amount", "0", "mode", "set"), RiskLevel.LOW, true);
		}
		Matcher m = XP_PATTERN.matcher(n);
		if (!m.find()) return null;
		String mode = containsAny(n, "设为", "设置为", "set") ? "set" : "add";
		return candidate(ActionType.GIVE_XP, 0.85, "经验" + mode + " " + m.group(1),
			Map.of("amount", m.group(1), "mode", mode), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseKillSelf(String n) {
		if (!containsAny(n, "自杀", "杀死自己", "kill me", "kill self", "去死", "了结自己")) {
			return null;
		}
		return candidate(ActionType.KILL_SELF, 0.9, "自杀", Map.of(), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseRespawnSelf(String n) {
		if (!containsAny(n, "重生", "复活", "respawn", "回到出生点") || containsAny(n, "设置", "set")) {
			return null; // 避免和 SET_SPAWNPOINT 冲突
		}
		return candidate(ActionType.RESPAWN_SELF, 0.85, "强制重生", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseRideDismount(String n) {
		if (!containsAny(n, "下坐骑", "下马", "下车", "下船", "离开坐骑", "dismount")) {
			return null;
		}
		return candidate(ActionType.RIDE_DISMOUNT, 0.9, "离开坐骑", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseSpectate(String n) {
		if (!containsAny(n, "旁观", "spectate", "观察")) {
			return null;
		}
		return candidate(ActionType.SPECTATE, 0.7, "旁观模式", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseClearInventory(String n) {
		if (!containsAny(n, "清空背包", "清空物品栏", "清除背包", "clear inventory", "clear背包")) {
			return null;
		}
		return candidate(ActionType.CLEAR_INVENTORY, 0.95, "清空背包", Map.of(), RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseTagAdd(String n) {
		if (!containsAny(n, "加标签", "添加标签", "tag add", "给标签")) {
			return null;
		}
		Matcher m = Pattern.compile("标签\\s*([A-Za-z0-9_]+)|tag\\s+([A-Za-z0-9_]+)").matcher(n);
		if (!m.find()) return null;
		String tag = m.group(1) != null ? m.group(1) : m.group(2);
		return candidate(ActionType.TAG_ADD, 0.85, "给自己添加标签 " + tag,
			Map.of("tag", tag), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseTagRemove(String n) {
		if (!containsAny(n, "删除标签", "移除标签", "去标签", "tag remove")) {
			return null;
		}
		Matcher m = Pattern.compile("标签\\s*([A-Za-z0-9_]+)|tag\\s+remove\\s+([A-Za-z0-9_]+)").matcher(n);
		if (!m.find()) return null;
		String tag = m.group(1) != null ? m.group(1) : m.group(2);
		return candidate(ActionType.TAG_REMOVE, 0.85, "移除标签 " + tag,
			Map.of("tag", tag), RiskLevel.LOW, true);
	}

	// ============================================================
	//  Tier B 世界/方块
	// ============================================================

	private static IntentCandidate parseTime(String n) {
		if (!containsAny(n, "时间", "白天", "夜晚", "中午", "午夜", "day", "night", "noon", "midnight")) {
			return null;
		}
		String target = null;
		if (containsAny(n, "白天", "day")) target = "day";
		else if (containsAny(n, "夜晚", "night")) target = "night";
		else if (containsAny(n, "中午", "noon")) target = "noon";
		else if (containsAny(n, "午夜", "midnight")) target = "midnight";
		if (target == null) return null;
		return candidate(ActionType.SET_TIME, 0.9, "调整时间", Map.of("time", target), RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseWeather(String n) {
		if (!containsAny(n, "天气", "下雨", "晴天", "打雷", "雷暴", "雨天", "weather", "rain", "thunder", "clear")) {
			return null;
		}
		String weather = "clear";
		if (containsAny(n, "打雷", "雷暴", "thunder")) weather = "thunder";
		else if (containsAny(n, "下雨", "雨天", "rain")) weather = "rain";
		else if (containsAny(n, "晴天", "晴朗", "clear")) weather = "clear";
		return candidate(ActionType.SET_WEATHER, 0.9, "设置天气为" + weather,
			Map.of("weather", weather), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseSpawnEntity(String n) {
		if (!containsAny(n, "生成", "召唤", "spawn", "summon")) {
			return null;
		}
		String entityId = firstMatch(n, AliasTables.ENTITY_ALIASES);
		if (entityId == null) {
			// 退路:英文小写 ID 直接拼接
			for (String en : new String[]{"creeper", "zombie", "skeleton", "spider", "enderman", "pig", "cow", "sheep", "chicken", "horse", "villager"}) {
				if (n.contains(en)) {
					entityId = "minecraft:" + en;
					break;
				}
			}
		}
		if (entityId == null) return null;
		String count = "1";
		Matcher cnt = Pattern.compile("(\\d+)\\s*(?:只|头|个|匹)").matcher(n);
		if (cnt.find()) {
			count = cnt.group(1);
		} else {
			Matcher num = Pattern.compile("(\\d+)").matcher(n);
			if (num.find()) count = num.group(1);
		}
		return candidate(ActionType.SPAWN_ENTITY, 0.9, "生成实体",
			Map.of("entity", entityId, "count", count), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseKillDrops(String n) {
		if (!containsAny(n, "清掉落物", "清除掉落物", "清理掉落", "删除掉落", "清地上物品")) {
			return null;
		}
		return candidate(ActionType.KILL_DROPS, 0.9, "清除所有掉落物", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseSetBlock(String rawText, String normalized) {
		if (!containsAny(rawText, "放一个", "放一块", "放置", "setblock", "在脚下放", "在我脚下放")) {
			return null;
		}
		String blockId = extractBlockId(normalized);
		if (blockId == null) return null;
		Map<String, String> params = new HashMap<>();
		params.put("block", blockId);
		Matcher coords = COORDINATES.matcher(rawText);
		if (coords.find()) {
			params.put("x", coords.group(1));
			params.put("y", coords.group(2));
			params.put("z", coords.group(3));
			params.put("position", "explicit");
		} else {
			params.put("position", "feet");
		}
		return candidate(ActionType.SET_BLOCK, 0.85, "放置 " + blockId, params, RiskLevel.LOW, true);
	}

	private static IntentCandidate parseFillRegion(String rawText, String normalized) {
		boolean trigger = containsAny(rawText, "填充", "fill", "区域填充", "把") || containsAny(rawText, "区域");
		if (!trigger) return null;
		Matcher region = REGION_PATTERN.matcher(rawText);
		if (!region.find()) return null;
		String blockId = extractBlockId(normalized);
		if (blockId == null) return null;
		Map<String, String> params = new HashMap<>();
		params.put("block", blockId);
		params.put("x1", region.group(1));
		params.put("y1", region.group(2));
		params.put("z1", region.group(3));
		params.put("x2", region.group(4));
		params.put("y2", region.group(5));
		params.put("z2", region.group(6));
		return candidate(ActionType.FILL_REGION, 0.85, "填充区域为 " + blockId, params, RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseCloneRegion(String n) {
		if (!containsAny(n, "clone", "克隆", "复制区域")) {
			return null;
		}
		Matcher m = REGION_PATTERN.matcher(n);
		if (!m.find()) return null;
		// 简化:克隆到玩家当前位置
		Map<String, String> params = new HashMap<>();
		params.put("x1", m.group(1));
		params.put("y1", m.group(2));
		params.put("z1", m.group(3));
		params.put("x2", m.group(4));
		params.put("y2", m.group(5));
		params.put("z2", m.group(6));
		params.put("destinationMode", "feet");
		return candidate(ActionType.CLONE_REGION, 0.7, "克隆区域", params, RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseSetBiome(String rawText, String n) {
		if (!containsAny(rawText, "生物群系", "fillbiome", "biome")) {
			return null;
		}
		String biome = extractBiomeId(n);
		if (biome == null) return null;
		Matcher m = REGION_PATTERN.matcher(rawText);
		Map<String, String> params = new HashMap<>();
		params.put("biome", biome);
		if (m.find()) {
			params.put("x1", m.group(1));
			params.put("y1", m.group(2));
			params.put("z1", m.group(3));
			params.put("x2", m.group(4));
			params.put("y2", m.group(5));
			params.put("z2", m.group(6));
		}
		return candidate(ActionType.SET_BIOME, 0.85, "设置生物群系为 " + biome, params, RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseSetSpawnpoint(String n) {
		if (!containsAny(n, "设置出生点", "设置重生点", "setworldspawn", "spawnpoint")
			|| containsAny(n, "查询", "在哪")) {
			return null;
		}
		return candidate(ActionType.SET_SPAWNPOINT, 0.85, "设置出生点", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseDifficulty(String n) {
		if (!containsAny(n, "难度", "difficulty")) {
			return null;
		}
		String diff = null;
		if (containsAny(n, "和平", "peaceful")) diff = "peaceful";
		else if (containsAny(n, "简单", "easy")) diff = "easy";
		else if (containsAny(n, "普通", "normal", "中等")) diff = "normal";
		else if (containsAny(n, "困难", "hard")) diff = "hard";
		if (diff == null) return null;
		return candidate(ActionType.DIFFICULTY, 0.9, "难度设为 " + diff,
			Map.of("difficulty", diff), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseGameruleSet(String rawText, String n) {
		Matcher m;
		if (containsAny(rawText, "gamerule", "游戏规则", "规则")) {
			m = Pattern.compile("(?:gamerule|游戏规则|规则)\\s+([A-Za-z_]+)\\s+([A-Za-z0-9_]+)").matcher(rawText);
		} else {
			// "设置 keepInventory true" 模式
			m = Pattern.compile("设置\\s+([a-z][A-Za-z]+)\\s+(true|false|\\d+)").matcher(rawText);
		}
		if (!m.find()) return null;
		return candidate(ActionType.GAMERULE_SET, 0.85, "设置游戏规则 " + m.group(1) + "=" + m.group(2),
			Map.of("rule", m.group(1), "value", m.group(2)), RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseWorldBorder(String rawText, String n) {
		if (!containsAny(n, "世界边界", "worldborder", "边界")) {
			return null;
		}
		Matcher m = Pattern.compile("(\\d+)").matcher(n);
		if (!m.find()) return null;
		String value = m.group(1);
		String mode = containsAny(n, "中心", "center") ? "center" : "set";
		return candidate(ActionType.WORLD_BORDER, 0.8, "边界 " + mode + " " + value,
			Map.of("mode", mode, "value", value), RiskLevel.MEDIUM, true);
	}

	// ============================================================
	//  Tier C 查询/通信
	// ============================================================

	private static IntentCandidate parseTitleSelf(String rawText) {
		if (!containsAny(rawText, "显示标题", "给自己标题", "title", "标题")) {
			return null;
		}
		Matcher m = Pattern.compile("(?:标题|title)\\s*[:：]?\\s*(.+)").matcher(rawText);
		if (!m.find()) return null;
		String text = m.group(1).trim();
		if (text.isEmpty()) return null;
		return candidate(ActionType.TITLE_SELF, 0.85, "显示标题: " + text,
			Map.of("text", text), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseTellrawSelf(String rawText) {
		if (!containsAny(rawText, "tellraw", "原始消息", "发消息给自己")) {
			return null;
		}
		Matcher m = Pattern.compile("(?:tellraw|发消息给自己|原始消息)\\s*[:：]?\\s*(.+)").matcher(rawText);
		if (!m.find()) return null;
		String text = m.group(1).trim();
		return candidate(ActionType.TELLRAW_SELF, 0.8, "tellraw @s " + text,
			Map.of("text", text), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseLocateStructure(String n) {
		if (!containsAny(n, "定位", "locate", "找", "最近")) {
			return null;
		}
		if (!containsAny(n, "村庄", "神庙", "神殿", "小屋", "雪屋", "堡垒", "前哨", "掠夺者", "府邸", "海底", "末地", "structure", "遗迹")) {
			return null;
		}
		String structure = firstMatch(n, AliasTables.STRUCTURE_ALIASES);
		if (structure == null) return null;
		return candidate(ActionType.LOCATE_STRUCTURE, 0.85, "定位最近" + structure,
			Map.of("structure", structure), RiskLevel.READ_ONLY, false);
	}

	private static IntentCandidate parseSeed(String n) {
		if (!containsAny(n, "种子", "seed", "世界种子")) {
			return null;
		}
		return candidate(ActionType.SEED, 0.95, "查看世界种子", Map.of(), RiskLevel.READ_ONLY, false);
	}

	private static IntentCandidate parseBroadcastSay(String rawText) {
		if (!containsAny(rawText, "广播", "全服公告", "对所有人说", "say", "服务器广播")) {
			return null;
		}
		Matcher m = Pattern.compile("(?:广播|对所有人说|say)\\s*[:：]?\\s*(.+)").matcher(rawText);
		if (!m.find()) return null;
		String msg = m.group(1).trim();
		if (msg.isEmpty()) return null;
		return candidate(ActionType.BROADCAST_SAY, 0.85, "广播: " + msg,
			Map.of("message", msg), RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseMsgPlayer(String rawText) {
		if (!containsAny(rawText, "私聊", "私信", "msg", "tell", "对", "发给")) {
			return null;
		}
		Matcher m = Pattern.compile("(?:私聊|私信|msg|tell)\\s+([A-Za-z0-9_]{2,16})\\s+(.+)").matcher(rawText);
		if (!m.find()) return null;
		return candidate(ActionType.MSG_PLAYER, 0.85, "对 " + m.group(1) + " 私聊",
			Map.of("player", m.group(1), "message", m.group(2).trim()), RiskLevel.MEDIUM, true);
	}

	// ============================================================
	//  Tier D 计分/队伍
	// ============================================================

	private static IntentCandidate parseScoreboardAdd(String rawText) {
		if (!containsAny(rawText, "scoreboard", "计分板", "积分板")) {
			return null;
		}
		Matcher m = Pattern.compile("(?:scoreboard\\s+objectives\\s+add|计分板\\s+添加)\\s+([A-Za-z0-9_]+)\\s+([A-Za-z0-9_:.-]+)").matcher(rawText);
		if (!m.find()) return null;
		return candidate(ActionType.SCOREBOARD_ADD, 0.85, "添加计分项 " + m.group(1),
			Map.of("name", m.group(1), "criteria", m.group(2)),
			RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseTeamAdd(String rawText) {
		if (!containsAny(rawText, "team add", "添加队伍", "新建队伍", "创建队伍")) {
			return null;
		}
		Matcher m = Pattern.compile("(?:team\\s+add|添加队伍|新建队伍|创建队伍)\\s+([A-Za-z0-9_]+)").matcher(rawText);
		if (!m.find()) return null;
		return candidate(ActionType.TEAM_ADD, 0.85, "添加队伍 " + m.group(1),
			Map.of("name", m.group(1)), RiskLevel.MEDIUM, true);
	}

	// ============================================================
	//  Tier E 管理员
	// ============================================================

	private static IntentCandidate parseOpPlayer(String rawText) {
		return parseSinglePlayerAdminCommand(rawText, "op", ActionType.OP_PLAYER, "添加 op");
	}

	private static IntentCandidate parseDeopPlayer(String rawText) {
		return parseSinglePlayerAdminCommand(rawText, "deop", ActionType.DEOP_PLAYER, "撤销 op");
	}

	private static IntentCandidate parseKickPlayer(String rawText) {
		// "kick" 关键字不要与 "ban" 共用同一 regex,否则会先匹配成 BAN
		Matcher m = Pattern.compile("(?:kick|踢出)\\s+([A-Za-z0-9_]{2,16})(?:\\s+(.+))?").matcher(rawText);
		if (!m.find()) return null;
		Map<String, String> params = new HashMap<>();
		params.put("player", m.group(1));
		if (m.group(2) != null) params.put("reason", m.group(2).trim());
		return candidate(ActionType.KICK_PLAYER, 0.9, "踢出 " + m.group(1), params, RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseBanPlayer(String rawText) {
		Matcher m = Pattern.compile("(?:ban|封禁)\\s+([A-Za-z0-9_]{2,16})(?:\\s+(.+))?").matcher(rawText);
		if (!m.find()) return null;
		Map<String, String> params = new HashMap<>();
		params.put("player", m.group(1));
		if (m.group(2) != null) params.put("reason", m.group(2).trim());
		return candidate(ActionType.BAN_PLAYER, 0.9, "封禁 " + m.group(1), params, RiskLevel.HIGH, true);
	}

	private static IntentCandidate parsePardonPlayer(String rawText) {
		Matcher m = Pattern.compile("(?:pardon|解封)\\s+([A-Za-z0-9_]{2,16})").matcher(rawText);
		if (!m.find()) return null;
		return candidate(ActionType.PARDON_PLAYER, 0.9, "解封 " + m.group(1),
			Map.of("player", m.group(1)), RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseStopServer(String rawText) {
		boolean hasTrigger = containsAny(rawText, "关闭服务器", "停服", "停止服务器", "stop server", "/stop")
			|| containsAny(rawText, "服务器", "服", "server", "stop");
		if (!hasTrigger) return null;
		return candidate(ActionType.STOP_SERVER, 0.9, "关闭服务器", Map.of(), RiskLevel.CRITICAL, true);
	}

	private static IntentCandidate parseSaveAll(String rawText) {
		if (!containsAny(rawText, "保存世界", "save-all", "saveall", "存档")) {
			return null;
		}
		return candidate(ActionType.SAVE_ALL, 0.9, "保存世界", Map.of(), RiskLevel.LOW, true);
	}

	private static IntentCandidate parseRunCommand(String rawText) {
		String text = rawText == null ? "" : rawText.trim();
		Matcher m = RUN_COMMAND_PATTERN.matcher(text);
		if (!m.find()) return null;
		String command = MinecraftCommandSupport.normalizeCommand(m.group(1));
		if (command.isBlank()) return null;
		return candidate(ActionType.RUN_COMMAND, 0.98, "执行 Minecraft 原生命令",
			Map.of(MinecraftCommandSupport.COMMAND_PARAM, command), RiskLevel.HIGH, true);
	}

	// ============================================================
	//  辅助方法
	// ============================================================

	private static void addIfNotNull(List<IntentCandidate> candidates, IntentCandidate candidate) {
		if (candidate != null) {
			candidates.add(candidate);
		}
	}

	private static IntentCandidate candidate(ActionType type, double confidence, String summary,
			Map<String, String> parameters, RiskLevel riskLevel, boolean requiresConfirmation) {
		return new IntentCandidate(type, confidence, summary, parameters, riskLevel, false);
	}

	/** 从 alias 表中找到第一个键在 {@code text} 中出现的值;若全部不匹配返回 null。 */
	private static String firstMatch(String text, Map<String, String> aliases) {
		for (Map.Entry<String, String> entry : aliases.entrySet()) {
			if (text.contains(entry.getKey())) {
				return entry.getValue();
			}
		}
		return null;
	}

	/** 处理 Matcher.group(idx) 可能为 null 的场景;空值降级为 {@code fallback}。 */
	private static String nullSafeGroup(Matcher m, int group, String fallback) {
		String value = m.group(group);
		return (value == null || value.isEmpty()) ? fallback : value;
	}

	private static IntentCandidate parseSinglePlayerAdminCommand(String rawText, String root, ActionType type, String summary) {
		if (!containsAny(rawText, root)) {
			return null;
		}
		Matcher m = Pattern.compile(root + "\\s+([A-Za-z0-9_]{2,16})").matcher(rawText);
		if (!m.find()) return null;
		return candidate(type, 0.9, summary + " " + m.group(1),
			Map.of("player", m.group(1)), RiskLevel.HIGH, true);
	}

	private static String extractBlockId(String normalized) {
		String alias = firstMatch(normalized, AliasTables.BLOCK_ALIASES);
		if (alias != null) return alias;
		// 退路:识别 minecraft:id 形式或 *_block 后缀
		Matcher m = Pattern.compile("minecraft:[a-z_]+|[a-z_]+_block").matcher(normalized);
		if (m.find()) {
			String token = m.group();
			return token.contains(":") ? token : "minecraft:" + token;
		}
		return null;
	}

	private static String extractBiomeId(String normalized) {
		return firstMatch(normalized, AliasTables.BIOME_ALIASES);
	}

	private static String normalize(String rawText) {
		return rawText == null ? "" : rawText.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	private static boolean containsAny(String text, String... tokens) {
		for (String token : tokens) {
			if (text.contains(token.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private static String normalizeItem(String token) {
		if (token == null || token.isBlank()) {
			return "minecraft:stone";
		}
		String alias = AliasTables.ITEM_ALIASES.get(token);
		if (alias != null) return alias;
		return token.contains(":") ? token : "minecraft:" + token;
	}
}
