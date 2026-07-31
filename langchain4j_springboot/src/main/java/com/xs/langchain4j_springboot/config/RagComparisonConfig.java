package com.xs.langchain4j_springboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByLineSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * RAG 两种向量存储拓扑的“对比开关”演示配置。
 *
 * 切换方式（application.properties）：
 *   rag.strategy=isolated   -> 每业务一个独立 collection（物理隔离，默认）
 *   rag.strategy=shared     -> 单一 collection + source payload 过滤（灵活共享）
 *
 * 两种模式下，对外暴露的 bean 名字完全一致（legalAssistant / productAssistant），
 * 所以 Controller 代码无需随策略改变——切换策略即可直接对比“返回结果 / 行为差异”。
 */
@Configuration
public class RagComparisonConfig {

    @Value("${rag.strategy:isolated}")
    private String strategy;

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int grpcPort;

    @Value("${qdrant.rest-port:6333}")
    private int restPort;

    @Value("${rag.legal.collection:legal_docs}")
    private String legalCollection;

    @Value("${rag.product.collection:product_docs}")
    private String productCollection;

    @Value("${rag.shared.collection:shared_docs}")
    private String sharedCollection;

    @Value("${qdrant.dimension:0}")
    private int dimension;

    private static final Logger log = LoggerFactory.getLogger(RagComparisonConfig.class);

    // ============ 通用 helper ============

    /** 维度探测：优先用配置值，否则用 embedding 模型对样本文本实测。 */
    private int detectDim(EmbeddingModel em) {
        return dimension > 0 ? dimension : em.embed("probe-text").content().dimension();
    }

    /** 确保 collection 存在并清空旧数据：不存在则按 Cosine+当前维度创建；
     *  已存在则通过 delete-points 清空（避免 Windows 上 DELETE collection 偶发文件锁 500）。 */
    private void recreateCollection(String name, int dim) {
        String url = String.format("http://%s:%d/collections/%s", host, restPort, name);
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> get = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (get.statusCode() == 200) {
                // 已存在：清空所有 points 而非删 collection（Windows 上 DELETE collection 偶发 os error 5）
                log.info("[RAG] collection '{}' 已存在，清空旧数据。", name);
                try {
                    http.send(HttpRequest.newBuilder()
                                    .uri(URI.create(url + "/points/delete?wait=true"))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{\"filter\":{}}")).build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {
                    // 清空失败也不致命，新数据会覆盖旧 point id（Qdrant upsert 语义）
                }
                return;
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[RAG] 无法连接 Qdrant REST(" + host + ":" + restPort + ")，请先启动 Qdrant。原因: " + e.getMessage(), e);
        }
        // 不存在 → 创建
        String body = String.format("{\"vectors\":{\"size\":%d,\"distance\":\"Cosine\"}}", dim);
        try {
            HttpResponse<String> put = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (put.statusCode() == 200) {
                log.info("[RAG] 已创建 collection '{}' (dim={}, Cosine)。", name, dim);
            } else {
                throw new IllegalStateException("[RAG] 创建 collection '" + name + "' 失败: HTTP " + put.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("[RAG] 创建 collection '" + name + "' 失败。", e);
        }
    }

    /** 把某个文档灌库，并为每条 segment 打上 source 标签（共享模式靠它过滤）。
     *  切分后强制覆写 index 为全局唯一序号，避免 overlap 导致同一行被切成
     *  多个 segment 共享同一个 index，检索时看起来像"重复命中"。 */
    private void ingest(EmbeddingStore store, QwenEmbeddingModel em, String classPath, String source) {
        Document doc = ClassPathDocumentLoader.loadDocument(classPath, new TextDocumentParser());
        doc.metadata().put("source", source);
        DocumentByLineSplitter splitter = new DocumentByLineSplitter(150, 30);
        List<TextSegment> segments = splitter.split(doc);
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).metadata().put("index", i);
        }
        List<Embedding> embeddings = em.embedAll(segments).content();
        store.addAll(embeddings, segments);
        log.info("[RAG] 已灌库 {} 条 (source='{}')。", segments.size(), source);
    }

    /** 统一构建助手：store 决定去哪个 collection；filter 为 null 表示不过滤（隔离模式），非 null 表示按 source 过滤（共享模式）。 */
    private RagAssistant buildAssistant(ChatLanguageModel chat,
                                        StreamingChatLanguageModel stream,
                                        EmbeddingStore store,
                                        QwenEmbeddingModel em,
                                        Filter filter) {
        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder rb = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(em)
                .maxResults(5)
                .minScore(0.6);
        if (filter != null) {
            rb.filter(filter);
        }
        ContentRetriever retriever = rb.build();
        return AiServices.builder(RagAssistant.class)
                .chatLanguageModel(chat)
                .streamingChatLanguageModel(stream)
                .contentRetriever(retriever)
                .build();
    }

    // ============ 模式 A：isolated —— 每业务一个独立 collection ============

    @Bean
    @Qualifier("legalDocsStore")
    public EmbeddingStore legalDocsStore() {
        return QdrantEmbeddingStore.builder()
                .host(host).port(grpcPort).collectionName(legalCollection).build();
    }

    @Bean
    @Qualifier("productDocsStore")
    public EmbeddingStore productDocsStore() {
        return QdrantEmbeddingStore.builder()
                .host(host).port(grpcPort).collectionName(productCollection).build();
    }

    @Bean
    public CommandLineRunner setupIsolated(EmbeddingModel em,
                                           @Qualifier("legalDocsStore") EmbeddingStore legalStore,
                                           @Qualifier("productDocsStore") EmbeddingStore productStore,
                                           QwenEmbeddingModel model) {
        return args -> {
            int dim = detectDim(em);
            recreateCollection(legalCollection, dim);
            recreateCollection(productCollection, dim);
            ingest(legalStore, model, "rag/legal.txt", "legal");
            ingest(productStore, model, "rag/product.txt", "product");
        };
    }

    @Bean
    @Qualifier("legalAssistant")
    @ConditionalOnProperty(name = "rag.strategy", havingValue = "isolated", matchIfMissing = true)
    public RagAssistant legalAssistantIsolated(ChatLanguageModel chat,
                                               StreamingChatLanguageModel stream,
                                               @Qualifier("legalDocsStore") EmbeddingStore store,
                                               QwenEmbeddingModel em) {
        return buildAssistant(chat, stream, store, em, null);
    }

    @Bean
    @Qualifier("productAssistant")
    @ConditionalOnProperty(name = "rag.strategy", havingValue = "isolated", matchIfMissing = true)
    public RagAssistant productAssistantIsolated(ChatLanguageModel chat,
                                                 StreamingChatLanguageModel stream,
                                                 @Qualifier("productDocsStore") EmbeddingStore store,
                                                 QwenEmbeddingModel em) {
        return buildAssistant(chat, stream, store, em, null);
    }

    // ============ 模式 B：shared —— 单一 collection + source 过滤 ============

    @Bean
    @Qualifier("sharedStore")
    public EmbeddingStore sharedStore() {
        return QdrantEmbeddingStore.builder()
                .host(host).port(grpcPort).collectionName(sharedCollection).build();
    }

    @Bean
    public CommandLineRunner setupShared(EmbeddingModel em,
                                         @Qualifier("sharedStore") EmbeddingStore shared,
                                         QwenEmbeddingModel model) {
        return args -> {
            int dim = detectDim(em);
            recreateCollection(sharedCollection, dim);
            // 两份文档灌进同一个 collection，靠 source 字段区分业务
            ingest(shared, model, "rag/legal.txt", "legal");
            ingest(shared, model, "rag/product.txt", "product");
        };
    }

    @Bean
    @Qualifier("legalAssistant")
    @ConditionalOnProperty(name = "rag.strategy", havingValue = "shared")
    public RagAssistant legalAssistantShared(ChatLanguageModel chat,
                                             StreamingChatLanguageModel stream,
                                             @Qualifier("sharedStore") EmbeddingStore store,
                                             QwenEmbeddingModel em) {
        // 关键：在共享 collection 上，用 Filter 把检索限定在 source=='legal'
        Filter filter = MetadataFilterBuilder.metadataKey("source").isEqualTo("legal");
        return buildAssistant(chat, stream, store, em, filter);
    }

    @Bean
    @Qualifier("productAssistant")
    @ConditionalOnProperty(name = "rag.strategy", havingValue = "shared")
    public RagAssistant productAssistantShared(ChatLanguageModel chat,
                                                StreamingChatLanguageModel stream,
                                                @Qualifier("sharedStore") EmbeddingStore store,
                                                QwenEmbeddingModel em) {
        Filter filter = MetadataFilterBuilder.metadataKey("source").isEqualTo("product");
        return buildAssistant(chat, stream, store, em, filter);
    }
}
/**
 ```
 feat: 去掉 RagComparisonConfig 中 store/灌库 Runner 的 @ConditionalOnProperty

 原因
 原先 legalDocsStore / productDocsStore / sharedStore 及 setupIsolated / setupShared
 均按 rag.strategy 互斥创建，导致非当前策略的 collection 根本不存在，/rag/search 端点
 无法同时对比两种拓扑的召回质量。

 改动
 去掉上述 5 处 @ConditionalOnProperty，使三个 collection 启动时全部创建并灌库。
 助手 Bean（legalAssistant / productAssistant）仍按 rag.strategy 互斥切换，保持不变。

 对现有业务的影响
 无。新增的 EmbeddingStore 全部标注 @Qualifier，AiConfig 中无注解的 embeddingStore
 注入不受干扰，启动类灌库入口同样不受干扰。仅启动时多约 20+ 条 embedding API 调用和
 少量 Qdrant 存储开销。
 ```
 */