package com.xs.langchain4j_springboot.config;

import com.xs.langchain4j_springboot.service.ToolsService;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.*;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Configuration
public class AiConfig {

    public interface Assistant {
        String chat(String message);
        // 流式响应
        TokenStream stream(String message);

        @SystemMessage("""
                您是“Tuling”航空公司的客户聊天支持代理。请以友好、乐于助人且愉快的方式来回复。
                您正在通过在线聊天系统与客户互动。
                在提供有关预订或取消预订的信息之前，您必须始终从用户处获取以下信息：预订号、客户姓名。
                请讲中文。
                今天的日期是 {{current_date}}.
                """)
        TokenStream stream(@UserMessage String message,@V("current_date") String currentDate);
    }

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6334}")
    private int qdrantPort;

    @Value("${qdrant.collection-name:langchain4j_demo}")
    private String qdrantCollection;

    @Value("${qdrant.rest-port:6333}")
    private int qdrantRestPort;

    @Value("${qdrant.dimension:0}")
    private int qdrantDimension;

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public EmbeddingStore embeddingStore() {
        // 使用 Qdrant 作为向量存储（Windows 原生，替代内存版）。
        // collection 需预先在 Qdrant 中创建，维度须与 embedding 模型一致（默认 text-embedding-v2 = 1536）。
        return QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantPort)
                .collectionName(qdrantCollection)
                .build();
    }

    /**
     * 启动时确保 Qdrant 中存在目标 collection：不存在则按当前 embedding 模型维度自动创建，
     * 已存在则跳过（若维度与当前模型不符会打印告警，需手动删除重建）。
     * 这样改 embedding 模型或 collection 名时，只需改 application.properties，无需手动打 REST 建库。
     */
    @Bean
    public CommandLineRunner initQdrantCollection(EmbeddingModel embeddingModel) {
        return args -> {
            // 1) 确定向量维度：优先用配置值，否则用 embedding 模型对样本文本实测
            int dim = qdrantDimension > 0
                    ? qdrantDimension
                    : embeddingModel.embed("init-probe").content().dimension();
            log.info("Qdrant 目标 collection '{}'，embedding 维度 = {}", qdrantCollection, dim);

            // 2) 通过 REST(6333) 检查 collection 是否已存在
            String url = String.format("http://%s:%d/collections/%s", qdrantHost, qdrantRestPort, qdrantCollection);
            HttpClient http = HttpClient.newHttpClient();
            try {
                HttpResponse<String> getResp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                if (getResp.statusCode() == 200) {
                    // 已存在：校验维度是否匹配
                    JsonNode vectors = new ObjectMapper().readTree(getResp.body())
                            .path("result").path("vectors");
                    if (!vectors.isMissingNode() && vectors.has("size")
                            && vectors.get("size").asInt() != dim) {
                        log.warn("Collection '{}' 已存在但维度={} ≠ 当前模型维度={}。"
                                        + "请删除该 collection 后用新维度重建，否则 upsert 会报维度不匹配。",
                                qdrantCollection, vectors.get("size").asInt(), dim);
                    } else {
                        log.info("Qdrant collection '{}' 已存在，跳过创建。", qdrantCollection);
                    }
                    return;
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "无法连接 Qdrant REST(" + qdrantHost + ":" + qdrantRestPort
                                + ")，请先启动 Qdrant(start.bat)。原因: " + e.getMessage(), e);
            }

            // 3) 不存在 -> 创建（Cosine 距离，与 embedding 模型最匹配）
            String body = String.format("{\"vectors\":{\"size\":%d,\"distance\":\"Cosine\"}}", dim);
            HttpResponse<String> putResp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (putResp.statusCode() == 200) {
                log.info("已自动创建 Qdrant collection '{}' (dim={}, Cosine)。", qdrantCollection, dim);
            } else {
                throw new IllegalStateException(
                        "创建 Qdrant collection '" + qdrantCollection + "' 失败: HTTP "
                                + putResp.statusCode() + " " + putResp.body());
            }
        };
    }

    @Bean
    public Assistant assistant(ChatLanguageModel qwenChatModel,
                               StreamingChatLanguageModel qwenStreamingChatModel,
                               ToolsService toolsService,
                               EmbeddingStore embeddingStore,
                               QwenEmbeddingModel qwenEmbeddingModel
    ) {
        // 对话记忆
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        // 内容检索器
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(qwenEmbeddingModel)
                .maxResults(5) // 最相似的5个结果
                .minScore(0.6) // 只找相似度在0.6以上的内容
                .build();

        // 为Assistant动态代理对象  chat  --->  对话内容存储ChatMemory----> 聊天记录ChatMemory取出来 ---->放入到当前对话中
        Assistant assistant = AiServices.builder(Assistant.class)
                .tools(toolsService)
                .contentRetriever(contentRetriever)
                .chatLanguageModel(qwenChatModel)
                .streamingChatLanguageModel(qwenStreamingChatModel)
                .chatMemory(chatMemory)
                .build();

        return  assistant;
    }



    public interface AssistantUnique {

        String chat(@MemoryId int memoryId, @UserMessage String userMessage);
        // 流式响应
        TokenStream stream(@MemoryId int memoryId, @UserMessage String userMessage);
    }

    @Bean
    public AssistantUnique assistantUnique(ChatLanguageModel qwenChatModel,
                                           StreamingChatLanguageModel qwenStreamingChatModel) {

        AssistantUnique assistant = AiServices.builder(AssistantUnique.class)
                .chatLanguageModel(qwenChatModel)
                .streamingChatLanguageModel(qwenStreamingChatModel)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.builder().maxMessages(10)
                                .id(memoryId).build()
                )
                .build();

        return assistant;
    }





    @Bean
    public AssistantUnique assistantUniqueStore(ChatLanguageModel qwenChatModel,
                                                StreamingChatLanguageModel qwenStreamingChatModel,
                                                PersistentChatMemoryStore store) {

        ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();

        return AiServices.builder(AssistantUnique.class)
                .chatLanguageModel(qwenChatModel)
                .streamingChatLanguageModel(qwenStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}