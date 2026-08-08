package com.xs.langchain4j_springboot;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Langchain4jSpringbootApplicationTests {

    @Autowired
    private EmbeddingModel embeddingModel;
    @Test
    void contextLoads() {
        int dimension = embeddingModel.embed("probe-text123").content().dimension();
        System.out.println("dimension = " + dimension);
    }

}
