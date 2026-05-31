package com.example.csdemo

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class FaqAugmentTest {

    @Test
    fun `build returns the query unchanged when no faqs are provided`() {
        val query = "返品ポリシーを教えてください"

        assertEquals(query, FaqAugment.build(query, emptyList()))
    }

    @Test
    fun `build prepends FAQ items and ends with the question marker followed by the query`() {
        val query = "返品ポリシーを教えてください"
        val faqs = listOf(
            "返品ポリシー: 30 日以内であれば返品可能です。",
            "返金: 受領後 5〜7 営業日で処理します。",
        )

        val augmented = FaqAugment.build(query, faqs)

        assertTrue(augmented.startsWith(FaqAugment.HEADER))
        faqs.forEach { assertTrue("- $it" in augmented) }
        assertTrue(augmented.endsWith("${FaqAugment.QUESTION_MARKER}$query"))
    }

    @Test
    fun `StripFaqContextPreProcessor strips FAQ block from augmented user message`() {
        val original = "返品ポリシーを教えてください"
        val augmented = FaqAugment.build(original, listOf("返品ポリシー: 30 日以内であれば返品可能です。"))
        val input = listOf(userMessage(augmented))

        val output = StripFaqContextPreProcessor().preprocess(input)

        assertEquals(1, output.size)
        assertEquals(original, (output[0] as Message.User).content)
    }

    @Test
    fun `StripFaqContextPreProcessor leaves non-augmented user messages untouched`() {
        val plain = userMessage("注文 12345 はどこですか")

        val output = StripFaqContextPreProcessor().preprocess(listOf(plain))

        assertSame(plain, output[0])
    }

    @Test
    fun `StripFaqContextPreProcessor leaves assistant messages untouched`() {
        val assistant = Message.Assistant(
            content = "${FaqAugment.HEADER} ...",
            metaInfo = ResponseMetaInfo(timestamp = EPOCH),
        )

        val output = StripFaqContextPreProcessor().preprocess(listOf(assistant))

        assertSame(assistant, output[0])
    }

    @Test
    fun `StripFaqContextPreProcessor keeps message if question marker is missing`() {
        val malformed = userMessage("${FaqAugment.HEADER} (お客様の質問マーカーがない)")

        val output = StripFaqContextPreProcessor().preprocess(listOf(malformed))

        assertSame(malformed, output[0])
    }

    private fun userMessage(content: String): Message.User =
        Message.User(content = content, metaInfo = RequestMetaInfo(timestamp = EPOCH))

    companion object {
        private val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
    }
}
