package com.xs.langchain4j_springboot.config;

import dev.langchain4j.service.TokenStream;

/**
 * 对比演示用的 RAG 助手接口。
 * 与 AiConfig.Assistant（航空客服基线）解耦，专注于“向量检索结果是否答对”的对比。
 */
public interface RagAssistant {

    String chat(String message);

    TokenStream stream(String message);
}
