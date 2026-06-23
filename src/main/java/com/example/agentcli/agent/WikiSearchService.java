package com.example.agentcli.agent;

import java.util.Optional;

/**
 * Wiki 搜索抽象:实现可以是 {@link MediaWikiSearchService} 或 {@link DisabledWikiSearchService}。
 */
public interface WikiSearchService {
	WikiSearchState state();

	Optional<WikiArticle> search(String query);
}
