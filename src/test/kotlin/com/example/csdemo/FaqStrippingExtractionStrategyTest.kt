package com.example.csdemo

import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class FaqStrippingExtractionStrategyTest {

    @Test
    fun `FAQ block 付き user message は original query 部分だけ抽出される`() = runBlocking {
        val augmented = FaqAugment.build(
            query = "通常配送はどのくらいかかりますか",
            faqs = listOf("配送日数: 通常配送は 3〜5 営業日"),
        )
        val messages = listOf(userMessage(augmented))

        val records = FaqStrippingExtractionStrategy().extract(messages)

        assertEquals(1, records.size)
        assertEquals("通常配送はどのくらいかかりますか", records[0].content)
    }

    @Test
    fun `FAQ block なしの user message はそのまま record 化される`() = runBlocking {
        val messages = listOf(userMessage("私の名前は田中太郎です"))

        val records = FaqStrippingExtractionStrategy().extract(messages)

        assertEquals(1, records.size)
        assertEquals("私の名前は田中太郎です", records[0].content)
    }

    @Test
    fun `Assistant message は対象外`() = runBlocking {
        val messages = listOf(
            userMessage("私の名前は田中太郎です"),
            assistantMessage("田中太郎様、こんにちは"),
        )

        val records = FaqStrippingExtractionStrategy().extract(messages)

        assertEquals(1, records.size)
        assertEquals("私の名前は田中太郎です", records[0].content)
    }

    @Test
    fun `metadata に messageRole と timestampMs が含まれる`() = runBlocking {
        val messages = listOf(userMessage("test"))

        val records = FaqStrippingExtractionStrategy().extract(messages)

        assertTrue(records[0] is MemoryRecord)
        val metadata = records[0].metadata
        assertEquals("User", metadata["messageRole"])
        assertEquals(EPOCH.toEpochMilliseconds(), metadata["timestampMs"])
    }

    private fun userMessage(content: String): Message.User =
        Message.User(content = content, metaInfo = RequestMetaInfo(timestamp = EPOCH))

    private fun assistantMessage(content: String): Message.Assistant =
        Message.Assistant(content = content, metaInfo = ResponseMetaInfo(timestamp = EPOCH))

    companion object {
        private val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
    }
}
