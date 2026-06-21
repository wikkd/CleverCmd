package com.example.agentcli.agent;

import java.util.List;

public record IntentParseResult(
	String normalizedText,
	List<IntentCandidate> candidates,
	boolean clarificationRequired,
	String clarificationPrompt
) {
	public static IntentParseResult none(String normalizedText, String prompt) {
		return new IntentParseResult(normalizedText, List.of(), true, prompt);
	}
}

