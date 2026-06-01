package com.example.csdemo

import ai.koog.agents.longtermmemory.ingestion.extraction.ExtractionStrategy
import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.prompt.message.Message
import ai.koog.rag.base.TextDocument

/**
 * Step 5-A14 (A18): user message から FAQ block を strip した content を LongTermMemory に ingestion する [ExtractionStrategy]。
 *
 * 動機 (学び 52, 60): `augmentWithFaq` で生成した「以下の FAQ を参考にしてください: …」付きの
 * augmented prompt が、`LongTermMemory.ingestion` を経て record の content として保存されてしまう。
 * [StripFaqContextPreProcessor] は ChatMemory pipeline 用で LTM ingestion には効かないため、
 * ExtractionStrategy 側でも同じ strip 処理を行う。
 *
 * 挙動:
 * - User role の message のみ抽出 (FilteringExtractionStrategy の default と揃える方針より、
 *   demo の用途では「お客様の発話を覚える」目的に絞るため User 限定)
 * - content が FAQ block 始まりなら [FaqAugment.stripFaqBlock] で original query 部分だけ抽出
 * - metadata に messageRole / timestampMs を保存 (FilteringExtractionStrategy と互換)
 */
internal class FaqStrippingExtractionStrategy : ExtractionStrategy {
    override suspend fun extract(messages: List<Message>): List<TextDocument> {
        return messages
            .filterIsInstance<Message.User>()
            .map { msg ->
                MemoryRecord(
                    content = FaqAugment.stripFaqBlock(msg.content),
                    metadata = mapOf(
                        MESSAGE_ROLE_FIELD to msg.role.name,
                        TIMESTAMP_FIELD to msg.metaInfo.timestamp.toEpochMilliseconds(),
                    ),
                )
            }
    }

    private companion object {
        private const val MESSAGE_ROLE_FIELD = "messageRole"
        private const val TIMESTAMP_FIELD = "timestampMs"
    }
}
