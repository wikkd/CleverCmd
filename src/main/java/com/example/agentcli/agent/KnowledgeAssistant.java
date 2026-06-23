package com.example.agentcli.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 当自然语言解析失败(LLM 不可用 + 规则也匹配不上)时,尝试调 wiki 兜底回答。
 *
 * <p>只有当输入里包含知识查询触发词(是什么/who is/what is 等)才会启动 wiki 搜索,
 * 普通命令(give/tp/time 等)不会进入 wiki 流程。</p>
 */
final class KnowledgeAssistant {
	private static final Pattern COMMAND_LIKE = Pattern.compile("\\b(give|teleport|tp|time|gamemode|help|status|undo|cancel)\\b");
	private static final Pattern KNOWLEDGE_CUE = Pattern.compile(
		"(what is|who is|tell me about|explain|wiki|wiki search|minecraft wiki|是什么|是谁|介绍|解释|百科|查一下|告诉我|知识点|为什么|怎么)"
	);

	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "agentcli-wiki-search");
		thread.setDaemon(true);
		return thread;
	});
	private final WikiSearchService wikiSearchService;

	KnowledgeAssistant() {
		this(WikiSearchServiceFactory.get());
	}

	KnowledgeAssistant(WikiSearchService wikiSearchService) {
		this.wikiSearchService = wikiSearchService;
	}

	WikiSearchState wikiState() {
		return wikiSearchService.state();
	}

	Optional<String> answerIfHelpful(String rawText, AgentContext context) {
		return resolveReply(rawText, context);
	}

	boolean startAsyncLookup(ServerCommandSource source, String rawText, AgentContext context, String fallbackPrompt) {
		String query = extractQuery(rawText);
		if (!shouldAttempt(rawText, query)) {
			return false;
		}
		if (wikiSearchService.state() != WikiSearchState.AVAILABLE) {
			return false;
		}
		executor.submit(() -> {
			Optional<String> reply = resolveReply(rawText, context);
			String message = reply.orElse(fallbackPrompt);
			Formatting formatting = reply.isPresent() ? Formatting.AQUA : Formatting.YELLOW;
			source.getServer().execute(() -> source.sendFeedback(() -> Text.literal(message).formatted(formatting), false));
		});
		return true;
	}

	private Optional<String> resolveReply(String rawText, AgentContext context) {
		String query = extractQuery(rawText);
		if (!shouldAttempt(rawText, query)) {
			return Optional.empty();
		}
		if (wikiSearchService.state() == WikiSearchState.DISABLED) {
			return Optional.of("Wiki 搜索当前不可用，已回退到离线模式。");
		}
		Optional<WikiArticle> article = wikiSearchService.search(query);
		if (article.isPresent()) {
			return Optional.of(formatAnswer(article.get()));
		}
		if (wikiSearchService.state() == WikiSearchState.UNAVAILABLE) {
			return Optional.of("Wiki 搜索暂不可用，已回退到本地解析。");
		}
		return Optional.empty();
	}

	private static boolean shouldAttempt(String rawText, String query) {
		if (query.isBlank()) {
			return false;
		}
		String normalized = rawText == null ? "" : rawText.trim().toLowerCase(java.util.Locale.ROOT);
		if (COMMAND_LIKE.matcher(normalized).find() && !KNOWLEDGE_CUE.matcher(normalized).find()) {
			return false;
		}
		return normalized.contains("?") || KNOWLEDGE_CUE.matcher(normalized).find() || query.length() >= 3;
	}

	private static String extractQuery(String rawText) {
		String normalized = rawText == null ? "" : rawText.trim();
		String lower = normalized.toLowerCase(java.util.Locale.ROOT);
		String[] prefixes = {
			"what is ", "who is ", "tell me about ", "explain ", "wiki ", "wiki search ",
			"minecraft wiki ", "是什么 ", "是谁 ", "介绍一下 ", "介绍 ", "解释一下 ", "解释 "
		};
		for (String prefix : prefixes) {
			if (lower.startsWith(prefix)) {
				return normalized.substring(prefix.length()).trim();
			}
		}
		return normalized.replaceAll("[?？!！.。]+$", "").trim();
	}

	private static String formatAnswer(WikiArticle article) {
		return "Wiki: " + article.title() + " - " + article.summary() + " | 来源: " + article.url();
	}
}
