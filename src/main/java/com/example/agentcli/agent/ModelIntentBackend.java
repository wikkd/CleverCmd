package com.example.agentcli.agent;

public final class ModelIntentBackend implements IntentBackend {
	@Override
	public String name() {
		return "model";
	}

	@Override
	public IntentParseResult parse(String rawText, AgentContext context, AgentActionCatalog catalog) {
		return IntentParseResult.none(rawText == null ? "" : rawText.trim(), "模型后端尚未接入，当前使用规则解析。");
	}
}

