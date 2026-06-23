package com.example.agentcli.agent;

import java.util.Optional;

/**
 * Wiki 关闭时的空实现:始终返回 {@link WikiSearchState#DISABLED},搜索永远为空。
 */
final class DisabledWikiSearchService implements WikiSearchService {
	@Override
	public WikiSearchState state() {
		return WikiSearchState.DISABLED;
	}

	@Override
	public Optional<WikiArticle> search(String query) {
		return Optional.empty();
	}
}
