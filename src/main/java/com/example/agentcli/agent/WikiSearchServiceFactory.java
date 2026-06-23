package com.example.agentcli.agent;

/**
 * Wiki 服务单例工厂:读取 {@link WikiSearchConfig},在启动时一次性创建。
 * 启动后任何运行期失败都通过 {@code WikiSearchState} 表达,不重新创建实例。
 */
final class WikiSearchServiceFactory {
	private static final WikiSearchService SERVICE = create();

	private WikiSearchServiceFactory() {
	}

	static WikiSearchService get() {
		return SERVICE;
	}

	static WikiSearchState state() {
		return SERVICE.state();
	}

	private static WikiSearchService create() {
		try {
			WikiSearchConfig config = WikiSearchConfig.load();
			if (!config.enabled()) {
				return new DisabledWikiSearchService();
			}
			return new MediaWikiSearchService(config.endpoint(), config.timeout());
		} catch (RuntimeException ignored) {
			return new DisabledWikiSearchService();
		}
	}
}
