package com.example.csdemo

import kotlin.test.Test
import kotlin.test.assertEquals

class SupportGraphServiceTest {

    @Test
    fun `orderStatusReply uses the orderId when provided`() {
        val request = SupportRequest(
            intent = SupportIntent.ORDER_STATUS,
            orderId = "84721",
            summary = "注文 84721 の配送状況を確認したい",
        )

        assertEquals(
            "ご注文 84721 は現在処理中で、まもなく発送されます。",
            orderStatusReply(request),
        )
    }

    @Test
    fun `orderStatusReply falls back to unknown when orderId is null`() {
        val request = SupportRequest(
            intent = SupportIntent.ORDER_STATUS,
            orderId = null,
            summary = "配送状況を確認したい",
        )

        assertEquals(
            "ご注文 不明 は現在処理中で、まもなく発送されます。",
            orderStatusReply(request),
        )
    }
}
