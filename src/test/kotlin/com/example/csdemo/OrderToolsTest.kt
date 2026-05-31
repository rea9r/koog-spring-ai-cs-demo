package com.example.csdemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderToolsTest {

    @Test
    fun `getOrderStatus returns the dummy in-progress message`() {
        val tools = OrderTools(InMemoryCancelledOrderStore())

        assertEquals(
            "ご注文 ABC123 は現在処理中で、まもなく発送されます。",
            tools.getOrderStatus("ABC123"),
        )
    }

    @Test
    fun `cancelOrder returns acceptance message on first call`() {
        val tools = OrderTools(InMemoryCancelledOrderStore())

        val result = tools.cancelOrder("ABC123")

        assertTrue(
            result.startsWith("注文 ABC123 のキャンセルを受け付けました"),
            "expected acceptance message, got: $result",
        )
    }

    @Test
    fun `cancelOrder is idempotent — second call reports the existing cancellation`() {
        val store = InMemoryCancelledOrderStore()
        val tools = OrderTools(store)

        val first = tools.cancelOrder("ABC123")
        val second = tools.cancelOrder("ABC123")

        assertTrue(first.startsWith("注文 ABC123 のキャンセルを受け付けました"))
        assertTrue(
            second.startsWith("注文 ABC123 は既に "),
            "expected already-cancelled message, got: $second",
        )
        assertTrue(second.endsWith("にキャンセル受付済みです。再度の手続きは不要です。"))
    }

    @Test
    fun `different orderIds are tracked independently`() {
        val tools = OrderTools(InMemoryCancelledOrderStore())

        val a = tools.cancelOrder("A")
        val b = tools.cancelOrder("B")

        assertTrue(a.startsWith("注文 A のキャンセルを受け付けました"))
        assertTrue(b.startsWith("注文 B のキャンセルを受け付けました"))
    }
}
