package com.example.agentcli.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MediaWiki API 客户端,单实例 + 简单退避(连续 3 次失败后进入 5 分钟冷却)。
 *
 * <p>仅在 {@code WikiSearchConfig.enabled=true} 时由工厂创建;否则由
 * {@link DisabledWikiSearchService} 替代。</p>
 */
final class MediaWikiSearchService implements WikiSearchService {
	private static final Gson GSON = new Gson();
	private static final int MAX_ERRORS = 3;
	private static final long COOLDOWN_MILLIS = 5 * 60 * 1000L; // 5 minutes

	private final HttpClient httpClient;
	private final URI endpoint;
	private final Duration timeout;
	private final AtomicReference<WikiSearchState> state = new AtomicReference<>(WikiSearchState.AVAILABLE);
	private final AtomicInteger errorCount = new AtomicInteger(0);
	private final AtomicLong lastErrorTime = new AtomicLong(0);

	MediaWikiSearchService(URI endpoint, Duration timeout) {
		this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
		this.endpoint = endpoint;
		this.timeout = timeout;
	}

	@Override
	public WikiSearchState state() {
		WikiSearchState current = state.get();
		if (current == WikiSearchState.UNAVAILABLE && errorCount.get() < MAX_ERRORS) {
			long elapsed = System.currentTimeMillis() - lastErrorTime.get();
			if (elapsed > COOLDOWN_MILLIS) {
				state.compareAndSet(WikiSearchState.UNAVAILABLE, WikiSearchState.AVAILABLE);
				errorCount.set(0);
				return WikiSearchState.AVAILABLE;
			}
		}
		return current;
	}

	@Override
	public Optional<WikiArticle> search(String query) {
		if (state() != WikiSearchState.AVAILABLE) {
			return Optional.empty();
		}
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.isEmpty()) {
			return Optional.empty();
		}
		try {
			URI requestUri = buildSearchUri(trimmed);
			HttpRequest request = HttpRequest.newBuilder(requestUri)
				.timeout(timeout)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				onError();
				return Optional.empty();
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonObject queryNode = root.has("query") ? root.getAsJsonObject("query") : null;
			if (queryNode == null || !queryNode.has("search")) {
				return Optional.empty();
			}
			JsonArray search = queryNode.getAsJsonArray("search");
			if (search.isEmpty()) {
				return Optional.empty();
			}
			JsonObject best = search.get(0).getAsJsonObject();
			String title = safeString(best, "title");
			String snippet = sanitizeSnippet(safeString(best, "snippet"));
			if (title.isBlank() || snippet.isBlank()) {
				return Optional.empty();
			}
			errorCount.set(0);
			return Optional.of(new WikiArticle(title, snippet, buildArticleUrl(title)));
		} catch (Exception ignored) {
			onError();
			return Optional.empty();
		}
	}

	private void onError() {
		int count = errorCount.incrementAndGet();
		lastErrorTime.set(System.currentTimeMillis());
		if (count >= MAX_ERRORS) {
			state.set(WikiSearchState.UNAVAILABLE);
		}
	}

	private URI buildSearchUri(String query) {
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		String url = endpoint + "?action=query&list=search&format=json&formatversion=2&srsearch=" + encoded + "&srlimit=1&srprop=snippet&utf8=1";
		return URI.create(url);
	}

	private static String safeString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static String sanitizeSnippet(String snippet) {
		String stripped = snippet.replaceAll("<[^>]+>", " ");
		return stripped
			.replace("&quot;", "\"")
			.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&#039;", "'")
			.replace("&nbsp;", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static String buildArticleUrl(String title) {
		return "https://minecraft.wiki/w/" + title.replace(' ', '_');
	}
}
