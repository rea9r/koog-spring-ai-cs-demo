package com.example.csdemo

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrderToolsTest {

    @Test
    fun `getOrderStatus returns JSON with processing status`() {
        val tools = OrderTools(InMemoryCancelledOrderStore())

        val json = tools.getOrderStatus("ABC123")
        val result = Json.decodeFromString<GetOrderStatusResult>(json)

        assertEquals("ABC123", result.orderId)
        assertEquals("processing", result.status)
        assertNotNull(result.note)
    }

    @Test
    fun `cancelOrder returns accepted JSON on first call`() {
        val tools = OrderTools(InMemoryCancelledOrderStore())

        val json = tools.cancelOrder("ABC123")
        val result = Json.decodeFromString<CancelOrderResult>(json)

        assertEquals("accepted", result.status)
        assertEquals("ABC123", result.orderId)
    }

    @Test
    fun `cancelOrder returns already_cancelled JSON on second call with the original cancelledAt`() {
        val store = InMemoryCancelledOrderStore()
        val tools = OrderTools(store)

        val firstJson = tools.cancelOrder("ABC123")
        val secondJson = tools.cancelOrder("ABC123")

        val first = Json.decodeFromString<CancelOrderResult>(firstJson)
        val second = Json.decodeFromString<CancelOrderResult>(secondJson)

        assertEquals("accepted", first.status)
        assertEquals("already_cancelled", second.status)
        assertEquals(first.cancelledAt, second.cancelledAt, "second call must preserve the first cancellation time")
    }

    @Test
    fun `different orderIds are tracked independently`() {
        val tools = OrderTools(InMemoryCancelledOrderStore())

        val a = Json.decodeFromString<CancelOrderResult>(tools.cancelOrder("A"))
        val b = Json.decodeFromString<CancelOrderResult>(tools.cancelOrder("B"))

        assertEquals("accepted", a.status)
        assertEquals("A", a.orderId)
        assertEquals("accepted", b.status)
        assertEquals("B", b.orderId)
    }
}
