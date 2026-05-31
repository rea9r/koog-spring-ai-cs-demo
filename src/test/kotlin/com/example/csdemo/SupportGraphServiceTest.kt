package com.example.csdemo

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
