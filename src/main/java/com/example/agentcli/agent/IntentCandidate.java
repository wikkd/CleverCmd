package com.example.agentcli.agent;

import java.util.Map;

public record IntentCandidate(
	ActionType actionType,
	double confidence,
	String summary,
	Map<String, String> parameters,
	RiskLevel riskLevel,
	boolean requiresConfirmation
) {
}

