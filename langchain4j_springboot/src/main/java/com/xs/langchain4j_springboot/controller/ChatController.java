package com.xs.langchain4j_springboot.controller;

import com.xs.langchain4j_springboot.config.AiConfig;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

@RestController
@RequestMapping("/ai")
public class ChatController {

    @Autowired
    QwenChatModel chatModel;

    @Autowired
    QwenStreamingChatModel streamingChatModel;
    @RequestMapping("/chat")
    public String test(@RequestParam(defaultValue="你是谁") String message) {
        String chat = chatModel.chat(message);
        return chat;
    }


    @RequestMapping(value = "/stream",produces = "text/stream;charset=UTF-8")
    public Flux<String> stream(@RequestParam(defaultValue="你是谁") String message) {

        Flux<String> flux = Flux.create(fluxSink -> {

            streamingChatModel.chat(message, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    fluxSink.next(partialResponse);
//                    System.out.println(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    fluxSink.complete();
//                    System.out.println(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
//                    error.printStackTrace();
//                    fluxSink.error(error);
                }
            });


        });
        return flux;
    }


    @Autowired
    AiConfig.Assistant assistant;

    @RequestMapping(value = "/memory_chat")
    public String memoryChat(@RequestParam(defaultValue="我叫徐庶") String message) {
        return assistant.chat(message);
    }

    @RequestMapping(value = "/memory_stream_chat",produces ="text/stream;charset=UTF-8")
    public Flux<String> memoryStreamChat(@RequestParam(defaultValue="我是谁") String message, HttpServletResponse response) {
        TokenStream stream = assistant.stream(message, LocalDate.now().toString());

        return Flux.create(sink -> {
            stream.onPartialResponse(s -> sink.next(s))
                    .onCompleteResponse(c -> sink.complete())
                    .onError(sink::error)
                    .start();

        });
    }

    @Autowired
    AiConfig.AssistantUnique assistantUnique;

    @RequestMapping(value = "/memoryId_chat")
    public String memoryChat(@RequestParam(defaultValue="我是谁") String message, Integer userId) {
        return assistantUnique.chat(userId,message);
    }

    @Autowired
    @Qualifier("assistantUniqueStore")
    AiConfig.AssistantUnique assistantUniqueStore;

    @RequestMapping(value = "/memoryId_chat_store")
    public String memoryIdChatStore(@RequestParam(defaultValue="我是谁") String message, Integer userId) {
        return assistantUniqueStore.chat(userId, message);
    }
}
