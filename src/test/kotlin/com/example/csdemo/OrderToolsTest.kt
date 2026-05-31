package com.example.csdemo

import kotlin.test.Test
import kotlin.test.assertEquals

class OrderToolsTest {

    @Test
    fun `cancelOrder echoes the orderId in the response`() {
        val result = OrderTools().cancelOrder("ABC123")

        assertEquals(
            "注文 ABC123 のキャンセルを受け付けました。返金がある場合は別途ご案内します。",
            result,
        )
    }
}
