package com.xs.langchain4j_springboot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xs.langchain4j_springboot.config.RagAssistant;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/rag")
public class RagComparisonController {

    @Autowired
    @Qualifier("legalAssistant")
    RagAssistant legalAssistant;

    @Autowired
    @Qualifier("productAssistant")
    RagAssistant productAssistant;

    /** 用于把查询文本实时向量化（与灌库同一个 embedding 模型，保证同维度）。 */
    @Autowired
    EmbeddingModel embeddingModel;

    @Value("${rag.strategy:isolated}")
    String strategy;

    @Value("${qdrant.host:localhost}")
    String host;

    @Value("${qdrant.rest-port:6333}")
    int restPort;

    @Value("${rag.legal.collection:legal_docs}")
    String legalCollection;

    @Value("${rag.product.collection:product_docs}")
    String productCollection;

    @Value("${rag.shared.collection:shared_docs}")
    String sharedCollection;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** 当前生效的拓扑策略，方便对比时确认自己跑的是哪一套。 */
    @RequestMapping("/strategy")
    public String strategy() {
        return "current rag.strategy = " + strategy
                + "  (isolated=独立collection物理隔离 / shared=单collection+source过滤)";
    }

    /** 法律业务问答（隔离模式走 legal_docs；共享模式走 shared_docs 且过滤 source=='legal'）。 */
    @RequestMapping("/legal")
    public String legal(@RequestParam(defaultValue = "退票规则是什么？") String q) {
        return legalAssistant.chat(q);
    }

    /** 产品业务问答（隔离模式走 product_docs；共享模式走 shared_docs 且过滤 source=='product'）。 */
    @RequestMapping("/product")
    public String product(@RequestParam(defaultValue = "如何开机？") String q) {
        return productAssistant.chat(q);
    }

    /**
     * 裸检索端点：直接对向量库做最近邻搜索，返回命中片段与 score，
     * 同时把两种拓扑的“检索层”召回质量并排摆出来，方便量化对比：
     *   - isolated        : 查询对应业务的独立 collection（物理隔离，天然不可能跨域）
     *   - shared_filtered  : 共享 collection + source 过滤（逻辑隔离，靠 filter 拦住别的业务）
     *   - shared_unfiltered: 共享 collection 不过滤（故意的“反面教材”，用来暴露跨域泄露）
     * 与 /rag/legal、/rag/product 不同，这里不拼 LLM，只给你检索层的原始命中。
     */
    @RequestMapping("/search")
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(defaultValue = "legal") String source) {
        float[] vector = embeddingModel.embed(q).content().vector();

        String isolatedCol = "legal".equalsIgnoreCase(source) ? legalCollection : productCollection;
        List<Hit> isolated = rawSearch(isolatedCol, vector, null);
        List<Hit> sharedFiltered = rawSearch(sharedCollection, vector, filterFor(source));
        List<Hit> sharedUnfiltered = rawSearch(sharedCollection, vector, null);

        return new SearchResponse(q, source, isolated, sharedFiltered, sharedUnfiltered);
    }

    // ============ 内部 helper ============

    /** 构造 Qdrant 的 source 等值过滤条件（共享模式用来隔离业务域）。 */
    private String filterFor(String source) {
        return String.format("{\"must\":[{\"key\":\"source\",\"match\":{\"value\":\"%s\"}}]}", source);
    }

    /** 对指定 collection 做向量最近邻搜索（filterJson 为 null 表示不过滤），返回命中片段 + score。 */
    private List<Hit> rawSearch(String collection, float[] vector, String filterJson) {
        List<Hit> hits = new ArrayList<>();
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode vec = body.putArray("vector");
            for (float f : vector) {
                vec.add(f);
            }
            body.put("limit", 5);
            body.put("with_payload", true);
            body.put("with_vector", false);
            if (filterJson != null && !filterJson.isBlank()) {
                body.set("filter", mapper.readTree(filterJson));
            }

            String url = String.format("http://%s:%d/collections/%s/points/search", host, restPort, collection);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            JsonNode result = mapper.readTree(resp.body()).path("result");
            if (result.isArray()) {
                for (JsonNode p : result) {
                    JsonNode payload = p.path("payload");
                    hits.add(new Hit(
                            p.path("score").asDouble(),
                            payload.path("text_segment").asText(),
                            payload.path("source").asText(),
                            payload.path("file_name").asText(),
                            payload.path("index").asInt()));
                }
            }
        } catch (Exception e) {
            // 集合不存在或查询异常：返回空列表，不阻断另外两种模式的对比
            hits.clear();
        }
        return hits;
    }

    // ============ 响应 DTO ============

    /** 单条命中：相似度分数 + 原文片段 + 业务来源。 */
    public record Hit(double score, String text, String source, String file_name, int index) {}

    /** 一次裸检索的完整对比结果。 */
    public record SearchResponse(String query, String source,
                                 List<Hit> isolated,
                                 List<Hit> sharedFiltered,
                                 List<Hit> sharedUnfiltered) {}
}
