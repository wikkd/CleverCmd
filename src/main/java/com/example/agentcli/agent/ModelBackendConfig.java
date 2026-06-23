package com.example.agentcli.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side reader for the AI config file ({@code agent-cli-ai.json}).
 *
 * <p>The same file is written by {@code AiConfigStore} on the client.
 * System properties / environment variables override individual fields.</p>
 */
record ModelBackendConfig(String apiBaseUrl, String apiKey, String modelId) {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModelBackendConfig.class);
	private static final String FILE_NAME = "agent-cli-ai.json";

	boolean isAvailable() {
		return !apiKey.isBlank() && !apiBaseUrl.isBlank();
	}

	static ModelBackendConfig load() {
		ModelBackendConfig fileConfig = loadFromFile();
		String apiBaseUrl = override("agentcli.ai.apiBaseUrl", "AGENTCLI_AI_API_BASE_URL", fileConfig.apiBaseUrl());
		String apiKey = override("agentcli.ai.apiKey", "AGENTCLI_AI_API_KEY", fileConfig.apiKey());
		String modelId = override("agentcli.ai.modelId", "AGENTCLI_AI_MODEL_ID", fileConfig.modelId());
		return new ModelBackendConfig(apiBaseUrl, apiKey, modelId);
	}

	private static ModelBackendConfig loadFromFile() {
		try {
			Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
			if (Files.notExists(path)) {
				return empty();
			}
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				return new ModelBackendConfig(
					safeString(root, "apiBaseUrl"),
					safeString(root, "apiKey"),
					safeString(root, "modelId")
				);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to load AI config", e);
			return empty();
		}
	}

	private static ModelBackendConfig empty() {
		return new ModelBackendConfig("", "", "");
	}

	private static String safeString(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) {
			return "";
		}
		return el.getAsString().trim();
	}

	private static String override(String propKey, String envKey, String fallback) {
		String prop = System.getProperty(propKey);
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv(envKey);
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		return fallback;
	}
}
