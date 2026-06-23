package com.example.agentcli.agent;

import java.net.URI;
import java.time.Duration;

/**
 * Wiki 配置:由系统属性或环境变量覆盖,默认指向 minecraft.wiki。
 */
record WikiSearchConfig(boolean enabled, URI endpoint, Duration timeout) {
	static WikiSearchConfig load() {
		boolean enabled = readBoolean("agentcli.wiki.enabled", "AGENTCLI_WIKI_ENABLED", true);
		String endpointValue = readString("agentcli.wiki.endpoint", "AGENTCLI_WIKI_ENDPOINT", "https://minecraft.wiki/w/api.php");
		long timeoutSeconds = readLong("agentcli.wiki.timeoutSeconds", "AGENTCLI_WIKI_TIMEOUT_SECONDS", 4L);
		return new WikiSearchConfig(enabled, URI.create(endpointValue), Duration.ofSeconds(Math.max(1L, timeoutSeconds)));
	}

	private static boolean readBoolean(String propertyKey, String envKey, boolean defaultValue) {
		String value = readString(propertyKey, envKey, Boolean.toString(defaultValue));
		return Boolean.parseBoolean(value);
	}

	private static long readLong(String propertyKey, String envKey, long defaultValue) {
		String value = readString(propertyKey, envKey, Long.toString(defaultValue));
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static String readString(String propertyKey, String envKey, String defaultValue) {
		String propertyValue = System.getProperty(propertyKey);
		if (propertyValue != null && !propertyValue.isBlank()) {
			return propertyValue.trim();
		}
		String envValue = System.getenv(envKey);
		if (envValue != null && !envValue.isBlank()) {
			return envValue.trim();
		}
		return defaultValue;
	}
}
