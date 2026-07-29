package com.xs.langchain4j_springboot.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbcTemplate;

    public PersistentChatMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sql = "SELECT messages FROM chat_memory WHERE memory_id = ?";
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, String.valueOf(memoryId));
        if (rows.isEmpty()) {
            // 该用户/会话第一次对话，还没有历史记录
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(rows.get(0));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        // upsert：同一 memory_id 覆盖整段消息（MessageWindowChatMemory 已裁剪好窗口）
        String sql = """
                INSERT INTO chat_memory (memory_id, messages)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE messages = VALUES(messages)
                """;
        jdbcTemplate.update(sql, String.valueOf(memoryId), json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE memory_id = ?", String.valueOf(memoryId));
    }
}
