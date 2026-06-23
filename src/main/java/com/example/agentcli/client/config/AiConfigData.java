package com.example.agentcli.client.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI 配置数据模型,由 Cloth Config UI 编辑,JSON 序列化到 {@code <config>/agent-cli-ai.json}。
 *
 * <p>包含三组信息:
 * <ul>
 *   <li>LLM 接入参数:apiBaseUrl / apiKey / modelId / maxItemCount</li>
 *   <li>动作白/黑名单:白名单内的动作在「全确认」模式下也会跳过 /agent T 直接执行</li>
 *   <li>安全开关:requireUniversalConfirmation / allowAdminCommands</li>
 * </ul>
 *
 * <p>所有 setter 不做线程同步;通过 {@link #copy()} 返回不可变副本以避免外部修改。
 * 默认构造函数给出"安全即用"配置(管理员指令全禁、关键危险指令全黑)。</p>
 */
public final class AiConfigData {
	private String apiBaseUrl;
	private String apiKey;
	private String modelId;
	private int maxItemCount;
	private List<String> blacklistedActions;
	private boolean requireUniversalConfirmation;
	private List<String> whitelistedActions;
	private boolean allowAdminCommands;

	public AiConfigData() {
		this("", "", AiModelPreset.DEEPSEEK_V4_FLASH.modelId(), 64, defaultBlacklist(), true, defaultWhitelist(), false);
	}

	public AiConfigData(String apiBaseUrl, String apiKey, String modelId) {
		this(apiBaseUrl, apiKey, modelId, 64, defaultBlacklist(), true, defaultWhitelist(), false);
	}

	public AiConfigData(String apiBaseUrl, String apiKey, String modelId, int maxItemCount) {
		this(apiBaseUrl, apiKey, modelId, maxItemCount, defaultBlacklist(), true, defaultWhitelist(), false);
	}

	public AiConfigData(String apiBaseUrl, String apiKey, String modelId, int maxItemCount, List<String> blacklistedActions) {
		this(apiBaseUrl, apiKey, modelId, maxItemCount, blacklistedActions, true, defaultWhitelist(), false);
	}

	public AiConfigData(String apiBaseUrl, String apiKey, String modelId, int maxItemCount, List<String> blacklistedActions,
			boolean requireUniversalConfirmation, List<String> whitelistedActions) {
		this(apiBaseUrl, apiKey, modelId, maxItemCount, blacklistedActions, requireUniversalConfirmation, whitelistedActions, false);
	}

	public AiConfigData(String apiBaseUrl, String apiKey, String modelId, int maxItemCount, List<String> blacklistedActions,
			boolean requireUniversalConfirmation, List<String> whitelistedActions, boolean allowAdminCommands) {
		this.apiBaseUrl = normalize(apiBaseUrl);
		this.apiKey = normalize(apiKey);
		this.modelId = AiModelPreset.fromModelId(modelId).modelId();
		this.maxItemCount = Math.max(1, Math.min(9999, maxItemCount));
		this.blacklistedActions = blacklistedActions == null ? defaultBlacklist() : new ArrayList<>(blacklistedActions);
		this.requireUniversalConfirmation = requireUniversalConfirmation;
		this.whitelistedActions = whitelistedActions == null ? defaultWhitelist() : new ArrayList<>(whitelistedActions);
		this.allowAdminCommands = allowAdminCommands;
	}

	// 默认黑名单:原有 5 项 + 新增的世界级/管理员级危险操作。
	// 玩家自身常用的低/中风险动作(GIVE_ITEM / HEAL / SET_BLOCK / FILL_REGION / SET_BIOME 等)不在此列,
	// 仅靠 PolicyEngine 的 permissionLevel 闸门 + 黑名单双重保护。
	private static List<String> defaultBlacklist() {
		List<String> list = new ArrayList<>();
		// 原有
		list.add("CLEAR_INVENTORY");
		list.add("SET_TIME");
		list.add("SET_WEATHER");
		list.add("SPAWN_ENTITY");
		list.add("RUN_COMMAND");
		// Tier B/D 高风险世界操作(默认禁用,需用户在配置中显式启用)
		list.add("BROADCAST_SAY");
		list.add("CLONE_REGION");
		list.add("GAMERULE_SET");
		list.add("SCOREBOARD_ADD");
		list.add("TEAM_ADD");
		list.add("KILL_DROPS");
		// Tier E 管理员指令(默认禁用,需 allowAdminCommands=true)
		list.add("OP_PLAYER");
		list.add("DEOP_PLAYER");
		list.add("KICK_PLAYER");
		list.add("BAN_PLAYER");
		list.add("PARDON_PLAYER");
		list.add("STOP_SERVER");
		list.add("SAVE_ALL");
		return list;
	}

	// 白名单默认填入低风险且高频的动作,玩家可免确认直达。
	private static List<String> defaultWhitelist() {
		return new ArrayList<>(Arrays.asList(
			"TELEPORT_SELF", "GIVE_EFFECT", "SET_GAMEMODE",
			"SET_BLOCK", "TITLE_SELF", "TELLRAW_SELF"));
	}

	public String apiBaseUrl() {
		return apiBaseUrl;
	}

	public void apiBaseUrl(String apiBaseUrl) {
		this.apiBaseUrl = normalize(apiBaseUrl);
	}

	public String apiKey() {
		return apiKey;
	}

	public void apiKey(String apiKey) {
		this.apiKey = normalize(apiKey);
	}

	public AiModelPreset modelPreset() {
		return AiModelPreset.fromModelId(modelId);
	}

	public void modelPreset(AiModelPreset modelPreset) {
		AiModelPreset preset = modelPreset == null ? AiModelPreset.DEEPSEEK_V4_FLASH : modelPreset;
		this.modelId = preset.modelId();
		if (apiBaseUrl.isBlank()) {
			this.apiBaseUrl = preset.defaultApiBaseUrl();
		}
	}

	public String modelId() {
		return modelId;
	}

	public void modelId(String modelId) {
		this.modelId = AiModelPreset.fromModelId(modelId).modelId();
	}

	public int maxItemCount() {
		return maxItemCount;
	}

	public void maxItemCount(int maxItemCount) {
		this.maxItemCount = Math.max(1, Math.min(9999, maxItemCount));
	}

	public List<String> blacklistedActions() {
		return blacklistedActions == null ? defaultBlacklist() : new ArrayList<>(blacklistedActions);
	}

	public void blacklistedActions(List<String> blacklistedActions) {
		this.blacklistedActions = blacklistedActions == null ? defaultBlacklist() : new ArrayList<>(blacklistedActions);
	}

	public boolean isBlacklisted(String actionName) {
		return blacklistedActions != null && blacklistedActions.contains(actionName);
	}

	public boolean requireUniversalConfirmation() {
		return requireUniversalConfirmation;
	}

	public void requireUniversalConfirmation(boolean requireUniversalConfirmation) {
		this.requireUniversalConfirmation = requireUniversalConfirmation;
	}

	public List<String> whitelistedActions() {
		return whitelistedActions == null ? defaultWhitelist() : new ArrayList<>(whitelistedActions);
	}

	public void whitelistedActions(List<String> whitelistedActions) {
		this.whitelistedActions = whitelistedActions == null ? defaultWhitelist() : new ArrayList<>(whitelistedActions);
	}

	public boolean isWhitelisted(String actionName) {
		return whitelistedActions != null && whitelistedActions.contains(actionName);
	}

	public boolean allowAdminCommands() {
		return allowAdminCommands;
	}

	public void allowAdminCommands(boolean allowAdminCommands) {
		this.allowAdminCommands = allowAdminCommands;
	}

	public AiConfigData copy() {
		return new AiConfigData(apiBaseUrl, apiKey, modelId, maxItemCount, blacklistedActions,
				requireUniversalConfirmation, whitelistedActions, allowAdminCommands);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}
}