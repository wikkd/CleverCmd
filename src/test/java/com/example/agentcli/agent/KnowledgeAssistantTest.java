package com.example.agentcli.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class KnowledgeAssistantTest {
	@Test
	void offlineWikiSearchFallsBackWithoutThrowing() {
		KnowledgeAssistant assistant = new KnowledgeAssistant(new DisabledWikiSearchService());

		Optional<String> reply = assistant.answerIfHelpful("what is redstone", null);

		assertTrue(reply.isPresent());
		assertTrue(reply.get().contains("离线"));
	}

	@Test
	void commandLikeInputDoesNotTriggerWikiSearch() {
		KnowledgeAssistant assistant = new KnowledgeAssistant(new DisabledWikiSearchService());

		Optional<String> reply = assistant.answerIfHelpful("give me 3 diamond", null);

		assertFalse(reply.isPresent());
	}
}
