package com.example.agentcli.client.config;

/**
 * 模型预设清单,用于 Cloth Config 下拉选择。
 *
 * <p>{@code fromModelId} 接受任意字符串,未识别时回落到 {@link #DEEPSEEK_V4_FLASH},
 * 以保证向后兼容(老的 JSON 配置不会因为新预设加入而被识别为"未知模型")。</p>
 */
public enum AiModelPreset {
	DEEPSEEK_V4_FLASH("deepseek-v4-flash", "DeepSeek v4 Flash", "https://api.deepseek.com/v1"),
	DEEPSEEK_V3_TERMINUS("deepseek-v3-terminus", "DeepSeek v3 Terminus", "https://api.deepseek.com/v1"),
	OPENAI_GPT_4O("gpt-4o", "OpenAI GPT-4o", "https://api.openai.com/v1"),
	OPENAI_GPT_4O_MINI("gpt-4o-mini", "OpenAI GPT-4o Mini", "https://api.openai.com/v1"),
	MIMO_V2_5("Xiaomi/MiMo-v2.5", "MiMo v2.5 (硅基流动)", "https://api.siliconflow.cn/v1");

	private final String modelId;
	private final String displayName;
	private final String defaultApiBaseUrl;

	AiModelPreset(String modelId, String displayName, String defaultApiBaseUrl) {
		this.modelId = modelId;
		this.displayName = displayName;
		this.defaultApiBaseUrl = defaultApiBaseUrl;
	}

	public String modelId() {
		return modelId;
	}

	public String displayName() {
		return displayName;
	}

	public String defaultApiBaseUrl() {
		return defaultApiBaseUrl;
	}

	public static AiModelPreset fromModelId(String modelId) {
		if (modelId != null) {
			for (AiModelPreset preset : values()) {
				if (preset.modelId.equalsIgnoreCase(modelId.trim())) {
					return preset;
				}
			}
		}
		return DEEPSEEK_V4_FLASH;
	}

	@Override
	public String toString() {
		return displayName;
	}
}
