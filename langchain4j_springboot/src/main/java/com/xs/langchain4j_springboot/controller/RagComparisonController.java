package com.xs.langchain4j_springboot.controller;

import com.xs.langchain4j_springboot.config.RagAssistant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag")
public class RagComparisonController {

    @Autowired
    @Qualifier("legalAssistant")
    RagAssistant legalAssistant;

    @Autowired
    @Qualifier("productAssistant")
    RagAssistant productAssistant;

    @Value("${rag.strategy:isolated}")
    String strategy;

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
}
