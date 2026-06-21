package com.example.agentcli.agent;

public interface IntentBackend {
	String name();

	IntentParseResult parse(String rawText, AgentContext context, AgentActionCatalog catalog);
}

