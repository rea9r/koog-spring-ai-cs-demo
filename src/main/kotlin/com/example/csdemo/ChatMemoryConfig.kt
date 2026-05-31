package com.example.csdemo

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository
import org.springframework.boot.context.properties.ConfigurationProperties
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

/**
 * Step 5-A8/E3: ChatMemory の window size を設定可能化する。
 *
 * 動機: A6 で発見した「同一 session で同じ動詞を繰り返すと past assistant の表現 pattern に
 * LLM が引きずられて tool 戻りが書き換えられる」現象（学び 34）への対処。window を絞ることで
 * past assistant の蓄積を抑え、pattern 強化を弱める。E3 の「history 無制限に伸びる」の
 * token cost 対策にもなる。
 */
@ConfigurationProperties(prefix = "koog.support.chat-memory")
data class ChatMemoryWindowProperties(
    val windowSize: Int = 4,
)

/**
 * 直近 N 件だけ keep する [ChatMemoryPreProcessor]。
 *
 * 注意: preprocess は load 時にも store 時にも呼ばれる（学び 5）。store 時に切ると永続化された
 * history も短くなり不可逆だが、本 demo では pattern 引きずり対策として「永続化も短くなる方が
 * 結果的に都合がいい」と受容する。production で「永続は完全、load で絞る」を分けたいなら
 * Spring AI 側の `ChatMemoryRepository` に window を持たせる方が筋。
 */
internal class WindowSizePreProcessor(private val maxSize: Int) : ChatMemoryPreProcessor {
    override fun preprocess(messages: List<Message>): List<Message> =
        if (messages.size <= maxSize) messages else messages.takeLast(maxSize)
}
