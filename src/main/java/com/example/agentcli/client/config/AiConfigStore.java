package com.example.agentcli.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 AI 配置的持久化 + TTL 缓存。
 *
 * <p>同一份 JSON 同时被 Cloth Config UI(写)和 server 端 PolicyEngine(读)消费。
 * 由于 Fabric client/server 的 classloader 不同,server 看到的不是 {@code config} 对象,
 * 而是每次启动重新从磁盘加载;为了避免 "Cloth Config 保存后服务端仍读到旧值" 的问题,
 * 这里加了 1 秒 TTL 自动刷新,以及 {@link #reload()} 强制重读,后者供 {@code /agent reload} 触发。</p>
 */
public final class AiConfigStore {
	private static final Logger LOGGER = LoggerFactory.getLogger(AiConfigStore.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "agent-cli-ai.json";

	// 缓存上次读取时间(毫秒),用于 TTL 刷新。
	// 直接每次读磁盘也能用,但 JSON 解析虽快却不是零成本;TTL 让频繁读取走缓存、
	// 配置变更后 1 秒内自动可见。
	private static volatile AiConfigData cached;
	private static volatile long cachedAtMillis;
	private static final long TTL_MILLIS = 1000L;

	private AiConfigStore() {
	}

	public static synchronized AiConfigData get() {
		long now = System.currentTimeMillis();
		if (cached == null || (now - cachedAtMillis) > TTL_MILLIS) {
			cached = loadFromDisk();
			cachedAtMillis = now;
		}
		return cached.copy();
	}

	public static synchronized void save(AiConfigData data) {
		AiConfigData sanitized = sanitize(data);
		cached = sanitized;
		cachedAtMillis = System.currentTimeMillis();
		writeToDisk(sanitized);
	}

	/**
	 * 强制从磁盘重新加载(忽略 TTL)。
	 * 用于:Cloth Config 保存后让服务端立即看到;或管理员通过 /agent reload-config 显式触发。
	 */
	public static synchronized void reload() {
		cached = loadFromDisk();
		cachedAtMillis = System.currentTimeMillis();
	}

	private static AiConfigData loadFromDisk() {
		Path path = configPath();
		if (Files.notExists(path)) {
			return new AiConfigData();
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			AiConfigData loaded = GSON.fromJson(reader, AiConfigData.class);
			return sanitize(loaded);
		} catch (Exception e) {
			LOGGER.warn("Failed to load AI config from {}", path, e);
			return new AiConfigData();
		}
	}

	private static void writeToDisk(AiConfigData data) {
		Path path = configPath();
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save AI config to {}", path, e);
		}
	}

	private static AiConfigData sanitize(AiConfigData data) {
		AiConfigData safe = data == null ? new AiConfigData() : data.copy();
		safe.apiBaseUrl(safe.apiBaseUrl());
		safe.apiKey(safe.apiKey());
		safe.modelPreset(safe.modelPreset());
		return safe;
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}