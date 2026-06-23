package com.example.agentcli.agent;

/**
 * Wiki 搜索结果三元组:title 用于展示,summary 截取自 MediaWiki snippet HTML 标签剥离后的纯文本,
 * url 指向 {@code https://minecraft.wiki/w/<Title>}。
 */
public record WikiArticle(String title, String summary, String url) {
}
