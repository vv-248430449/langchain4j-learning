-- langchain4j 对话记忆持久化建库建表脚本
-- 功能：多用户隔离 + 持久化（memory_id 作为主键实现隔离）

CREATE DATABASE IF NOT EXISTS `ai-learning`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `ai-learning`;

CREATE TABLE IF NOT EXISTS `chat_memory` (
    `memory_id`  VARCHAR(255) NOT NULL COMMENT '会话/用户标识（多用户隔离主键）',
    `messages`   JSON         NOT NULL COMMENT 'ChatMessage 列表的 JSON 序列化结果',
    `created_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`memory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='langchain4j 对话记忆持久化表';
