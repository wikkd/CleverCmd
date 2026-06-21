package com.example.agentcli.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleBasedIntentBackend implements IntentBackend {
	private static final Pattern COORDINATES = Pattern.compile("(-?\\d+)\\D+(-?\\d+)\\D+(-?\\d+)");
	private static final Pattern GIVE_PATTERN = Pattern.compile("(?:给我|给予|give\\s+me|give)\\s*(\\d+)?\\s*(?:个|份|x|X)?\\s*([\\p{IsHan}A-Za-z0-9:_-]+)");

	private static final Map<String, String> ITEM_ALIASES = Map.ofEntries(
		Map.entry("钻石", "minecraft:diamond"),
		Map.entry("石头", "minecraft:stone"),
		Map.entry("木头", "minecraft:oak_log"),
		Map.entry("原木", "minecraft:oak_log"),
		Map.entry("苹果", "minecraft:apple"),
		Map.entry("铁锭", "minecraft:iron_ingot"),
		Map.entry("金锭", "minecraft:gold_ingot")
	);

	@Override
	public String name() {
		return "rule";
	}

	@Override
	public IntentParseResult parse(String rawText, AgentContext context, AgentActionCatalog catalog) {
		String normalized = normalize(rawText);
		List<IntentCandidate> candidates = new ArrayList<>();

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

		IntentCandidate teleport = parseTeleport(normalized);
		if (teleport != null) {
			candidates.add(teleport);
		}

		IntentCandidate give = parseGive(normalized);
		if (give != null) {
			candidates.add(give);
		}

		IntentCandidate time = parseTime(normalized);
		if (time != null) {
			candidates.add(time);
		}

		IntentCandidate mode = parseGameMode(normalized);
		if (mode != null) {
			candidates.add(mode);
		}

		if (candidates.isEmpty()) {
			return IntentParseResult.none(normalized, "未识别到明确意图。你可以输入“帮助”查看支持的操作。");
		}
		return new IntentParseResult(normalized, candidates, false, null);
	}

	private static IntentCandidate candidate(ActionType actionType, double confidence, String summary, Map<String, String> parameters, RiskLevel riskLevel, boolean requiresConfirmation) {
		return new IntentCandidate(actionType, confidence, summary, parameters, riskLevel, requiresConfirmation);
	}

	private static boolean isHelp(String normalized) {
		return containsAny(normalized, "帮助", "help", "?", "能做什么", "支持什么");
	}

	private static boolean isStatus(String normalized) {
		return containsAny(normalized, "状态", "位置", "我在哪", "当前状态", "我的位置");
	}

	private static boolean isUndo(String normalized) {
		return containsAny(normalized, "撤销", "undo", "回退", "恢复上一步");
	}

	private static boolean isCancel(String normalized) {
		return containsAny(normalized, "取消", "cancel", "停止当前", "终止");
	}

	private static IntentCandidate parseTeleport(String normalized) {
		if (!containsAny(normalized, "传送", "tp", "teleport")) {
			return null;
		}
		Map<String, String> params = new HashMap<>();
		if (containsAny(normalized, "出生点", "出生", "spawn")) {
			params.put("target", "spawn");
			return candidate(ActionType.TELEPORT_SELF, 0.95, "传送到出生点", params, RiskLevel.MEDIUM, true);
		}
		if (containsAny(normalized, "原点", "world origin")) {
			params.put("target", "origin");
			return candidate(ActionType.TELEPORT_SELF, 0.9, "传送到原点", params, RiskLevel.MEDIUM, true);
		}
		Matcher matcher = COORDINATES.matcher(normalized);
		if (matcher.find()) {
			params.put("target", "coordinates");
			params.put("x", matcher.group(1));
			params.put("y", matcher.group(2));
			params.put("z", matcher.group(3));
			return candidate(ActionType.TELEPORT_SELF, 0.92, "按坐标传送", params, RiskLevel.MEDIUM, true);
		}
		return candidate(ActionType.TELEPORT_SELF, 0.6, "传送指令", params, RiskLevel.MEDIUM, true);
	}

	private static IntentCandidate parseGive(String normalized) {
		Matcher matcher = GIVE_PATTERN.matcher(normalized);
		if (!matcher.find()) {
			return null;
		}
		String count = matcher.group(1) == null ? "1" : matcher.group(1);
		String itemToken = matcher.group(2);
		String itemId = normalizeItem(itemToken);
		Map<String, String> params = Map.of("item", itemId, "count", count);
		return candidate(ActionType.GIVE_ITEM, 0.95, "给予物品", params, RiskLevel.LOW, true);
	}

	private static IntentCandidate parseTime(String normalized) {
		if (!containsAny(normalized, "时间", "白天", "夜晚", "中午", "午夜", "day", "night", "noon", "midnight")) {
			return null;
		}
		String target = null;
		if (containsAny(normalized, "白天", "day")) {
			target = "day";
		} else if (containsAny(normalized, "夜晚", "night")) {
			target = "night";
		} else if (containsAny(normalized, "中午", "noon")) {
			target = "noon";
		} else if (containsAny(normalized, "午夜", "midnight")) {
			target = "midnight";
		}
		if (target == null) {
			return null;
		}
		return candidate(ActionType.SET_TIME, 0.9, "调整时间", Map.of("time", target), RiskLevel.HIGH, true);
	}

	private static IntentCandidate parseGameMode(String normalized) {
		if (!containsAny(normalized, "模式", "切换", "生存", "创造", "冒险", "旁观", "gamemode")) {
			return null;
		}
		String mode = null;
		if (containsAny(normalized, "创造", "creative")) {
			mode = "creative";
		} else if (containsAny(normalized, "生存", "survival")) {
			mode = "survival";
		} else if (containsAny(normalized, "冒险", "adventure")) {
			mode = "adventure";
		} else if (containsAny(normalized, "旁观", "spectator")) {
			mode = "spectator";
		}
		if (mode == null) {
			return null;
		}
		return candidate(ActionType.SET_GAMEMODE, 0.9, "切换游戏模式", Map.of("mode", mode), RiskLevel.MEDIUM, true);
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
		String alias = ITEM_ALIASES.get(token);
		if (alias != null) {
			return alias;
		}
		if (token.contains(":")) {
			return token;
		}
		return "minecraft:" + token;
	}
}

