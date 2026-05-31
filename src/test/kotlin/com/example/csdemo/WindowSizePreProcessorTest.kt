package com.example.csdemo

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class WindowSizePreProcessorTest {

    @Test
    fun `件数が maxSize 以下ならそのまま返す`() {
        val processor = WindowSizePreProcessor(4)
        val messages = listOf(
            userMessage("u1"), assistantMessage("a1"),
            userMessage("u2"), assistantMessage("a2"),
        )

        assertEquals(messages, processor.preprocess(messages))
    }

    @Test
    fun `件数が maxSize を超えたら最後 N 件だけ残す`() {
        val processor = WindowSizePreProcessor(4)
        val messages = listOf(
            userMessage("u1"), assistantMessage("a1"),
            userMessage("u2"), assistantMessage("a2"),
            userMessage("u3"), assistantMessage("a3"),
        )

        val result = processor.preprocess(messages)

        assertEquals(4, result.size)
        assertEquals("u2", (result[0] as Message.User).content)
        assertEquals("a3", (result[3] as Message.Assistant).content)
    }

    @Test
    fun `maxSize 0 ならすべて drop`() {
        val processor = WindowSizePreProcessor(0)

        val result = processor.preprocess(listOf(userMessage("u1"), assistantMessage("a1")))

        assertEquals(emptyList(), result)
    }

    private fun userMessage(content: String): Message.User =
        Message.User(content = content, metaInfo = RequestMetaInfo(timestamp = EPOCH))

    private fun assistantMessage(content: String): Message.Assistant =
        Message.Assistant(content = content, metaInfo = ResponseMetaInfo(timestamp = EPOCH))

    companion object {
        private val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
    }
}
