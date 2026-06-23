package com.example.agentcli.client.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config 屏幕:三组面板 — AI 接入参数、指令黑名单、确认策略/白名单。
 *
 * <p>白名单生效逻辑:勾选后该动作在「全确认」开启时也免确认;取消「全确认」
 * 或清空白名单都会让白名单失效。</p>
 */
public final class AgentAiConfigScreen {
	private AgentAiConfigScreen() {
	}

	// 可被黑名单 / 白名单控制的指令(排除控制类指令 HELP / STATUS / UNDO / CANCEL)
	private static final String[] BLOCKABLE_ACTIONS = {
		"TELEPORT_SELF", "GIVE_ITEM", "SET_TIME", "SET_GAMEMODE",
		"SET_WEATHER", "GIVE_EFFECT", "HEAL", "CLEAR_INVENTORY", "SPAWN_ENTITY",
		"RUN_COMMAND"
	};
	private static final String[] ACTION_LABELS = {
		"传送", "给予物品", "设置时间", "切换游戏模式",
		"设置天气", "药水效果", "治疗", "清空背包", "生成实体",
		"原生命令"
	};

	public static Screen create(Screen parent) {
		AiConfigData config = AiConfigStore.get();

		// Pre-fill API URL from preset default if currently blank
		if (config.apiBaseUrl().isBlank()) {
			config.apiBaseUrl(config.modelPreset().defaultApiBaseUrl());
		}

		List<String> blacklist = new ArrayList<>(config.blacklistedActions());
		List<String> whitelist = new ArrayList<>(config.whitelistedActions());

		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Text.literal("Agent CLI AI 设置"));

		ConfigCategory aiCategory = builder.getOrCreateCategory(Text.literal("AI"));
		ConfigEntryBuilder entryBuilder = builder.entryBuilder();

		aiCategory.addEntry(entryBuilder.startStrField(Text.literal("API 地址"), config.apiBaseUrl())
			.setDefaultValue(config.modelPreset().defaultApiBaseUrl())
			.setSaveConsumer(config::apiBaseUrl)
			.build());

		aiCategory.addEntry(entryBuilder.startStrField(Text.literal("API Key"), config.apiKey())
			.setDefaultValue("")
			.setSaveConsumer(config::apiKey)
			.build());

		List<AiModelPreset> presets = Arrays.asList(AiModelPreset.values());
		aiCategory.addEntry(entryBuilder.startDropdownMenu(
				Text.literal("模型预设"),
				config.modelPreset(),
				AiModelPreset::fromModelId,
				p -> Text.literal(p.displayName()))
			.setDefaultValue(AiModelPreset.DEEPSEEK_V4_FLASH)
			.setSelections(presets)
			.setSaveConsumer(preset -> {
				config.modelPreset(preset);
			})
			.build());

		aiCategory.addEntry(entryBuilder.startIntField(Text.literal("物品数量上限"), config.maxItemCount())
			.setDefaultValue(64)
			.setMin(1)
			.setMax(9999)
			.setTooltip(Text.literal("给予物品时的最大数量限制(默认64)"))
			.setSaveConsumer(config::maxItemCount)
			.build());

		// 指令黑名单配置
		ConfigCategory blacklistCategory = builder.getOrCreateCategory(Text.literal("指令黑名单"));
		for (int i = 0; i < BLOCKABLE_ACTIONS.length; i++) {
			String action = BLOCKABLE_ACTIONS[i];
			String label = ACTION_LABELS[i];
			boolean blocked = blacklist.contains(action);
			blacklistCategory.addEntry(
				entryBuilder.startBooleanToggle(Text.literal(label + " (" + action + ")"), blocked)
					.setDefaultValue(defaultBlacklisted(action))
					.setTooltip(Text.literal("开启 = 屏蔽该指令,关闭 = 允许执行"))
					.setSaveConsumer(enabled -> {
						if (enabled) {
							if (!blacklist.contains(action)) blacklist.add(action);
						} else {
							blacklist.remove(action);
						}
						config.blacklistedActions(blacklist);
					})
					.build()
			);
		}

		// AI 指令确认策略(平铺在同一窗口)
		ConfigCategory confirmationCategory = builder.getOrCreateCategory(Text.literal("指令确认"));
		confirmationCategory.addEntry(
			entryBuilder.startBooleanToggle(Text.literal("所有 AI 指令都要确认"), config.requireUniversalConfirmation())
				.setDefaultValue(true)
				.setTooltip(Text.literal("开启后,任何 AI 解析出的指令都要输入 /agent T 才能执行;"
					+ "关闭后,除黑名单外都自动执行(默认开)"))
				.setSaveConsumer(config::requireUniversalConfirmation)
				.build()
		);
		confirmationCategory.addEntry(
			entryBuilder.startBooleanToggle(Text.literal("白名单内动作免确认"), true)
				.setDefaultValue(true)
				.setTooltip(Text.literal("总开关,关闭后白名单失效(默认开)"))
				.setSaveConsumer(enabled -> {
					// 占位:把"白名单启用"折叠进白名单本身(空白名单 = 等同于关闭)。
					// 这里仅作可见说明,实际生效由 whitelist 是否非空决定。
					if (!enabled) {
						whitelist.clear();
						config.whitelistedActions(whitelist);
					}
				})
				.build()
		);
		for (int i = 0; i < BLOCKABLE_ACTIONS.length; i++) {
			String action = BLOCKABLE_ACTIONS[i];
			String label = ACTION_LABELS[i];
			boolean whitelisted = whitelist.contains(action);
			confirmationCategory.addEntry(
				entryBuilder.startBooleanToggle(Text.literal("免确认: " + label + " (" + action + ")"), whitelisted)
					.setDefaultValue(defaultWhitelisted(action))
					.setTooltip(Text.literal("勾选后,该动作即使在「全确认」模式下也会跳过 /agent T 直接执行;"
						+ "关闭「全确认」开关或关闭「白名单启用」会让该选项失效"))
					.setSaveConsumer(enabled -> {
						if (enabled) {
							if (!whitelist.contains(action)) whitelist.add(action);
						} else {
							whitelist.remove(action);
						}
						config.whitelistedActions(whitelist);
					})
					.build()
			);
		}

		builder.setSavingRunnable(() -> AiConfigStore.save(config));
		return builder.build();
	}

	private static boolean defaultBlacklisted(String action) {
		return switch (action) {
			case "CLEAR_INVENTORY", "SET_TIME", "SET_WEATHER", "SPAWN_ENTITY", "RUN_COMMAND" -> true;
			default -> false;
		};
	}

	private static boolean defaultWhitelisted(String action) {
		return switch (action) {
			case "TELEPORT_SELF", "GIVE_EFFECT", "SET_GAMEMODE" -> true;
			default -> false;
		};
	}
}