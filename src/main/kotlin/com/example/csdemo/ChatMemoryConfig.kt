package com.example.csdemo

import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Step 3-2：Spring AI の [ChatMemoryRepository] Bean を明示的に提供する。
 * koog-spring-ai-starter-chat-memory の bridge は、この Bean を見つけて
 * Koog 側の `ChatHistoryProvider` として登録する。
 */
@Configuration
class ChatMemoryConfig {
    @Bean
    fun chatMemoryRepository(): ChatMemoryRepository = InMemoryChatMemoryRepository()
}
