package com.example.csdemo

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant

class SupportGraphServiceTest {

    @Test
    fun `orderStatusReply uses the orderId when provided`() {
        val request = SupportRequest(
            intent = SupportIntent.ORDER_STATUS,
            orderId = "84721",
            summary = "Check the status of order 84721",
        )

        assertEquals(
            "Your order 84721 is being processed and will ship soon.",
            orderStatusReply(request),
        )
    }

    @Test
    fun `orderStatusReply falls back to unknown when orderId is null`() {
        val request = SupportRequest(
            intent = SupportIntent.ORDER_STATUS,
            orderId = null,
            summary = "Check the status",
        )

        assertEquals(
            "Your order unknown is being processed and will ship soon.",
            orderStatusReply(request),
        )
    }

    @Test
    fun `StripFaqContextPreProcessor strips FAQ block from augmented user message`() {
        val augmented = """
            Reference the following FAQ items if relevant:
            - Our standard return policy allows returns within 30 days of delivery.
            - Refunds are processed within 5-7 business days.

            Customer question: What is your return policy?
        """.trimIndent()
        val input = listOf(userMessage(augmented))

        val output = StripFaqContextPreProcessor().preprocess(input)

        assertEquals(1, output.size)
        assertEquals("What is your return policy?", (output[0] as Message.User).content)
    }

    @Test
    fun `StripFaqContextPreProcessor leaves non-augmented user messages untouched`() {
        val plain = userMessage("Where is my order 12345?")

        val output = StripFaqContextPreProcessor().preprocess(listOf(plain))

        assertSame(plain, output[0])
    }

    @Test
    fun `StripFaqContextPreProcessor leaves assistant messages untouched`() {
        val assistant = Message.Assistant(
            content = "Reference the following FAQ items if relevant: ...",
            metaInfo = ResponseMetaInfo(timestamp = EPOCH),
        )

        val output = StripFaqContextPreProcessor().preprocess(listOf(assistant))

        assertSame(assistant, output[0])
    }

    @Test
    fun `StripFaqContextPreProcessor keeps message if question marker is missing`() {
        val malformed = userMessage("Reference the following FAQ items: (oops, no marker)")

        val output = StripFaqContextPreProcessor().preprocess(listOf(malformed))

        assertSame(malformed, output[0])
    }

    private fun userMessage(content: String): Message.User =
        Message.User(content = content, metaInfo = RequestMetaInfo(timestamp = EPOCH))

    companion object {
        private val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
    }
}
