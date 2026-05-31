package com.example.csdemo

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.slf4j.LoggerFactory

@LLMDescription("注文に関する操作を行うツール群。お客様の明示的な依頼に応じて呼ぶこと。")
class OrderTools : ToolSet {

    @Tool
    @LLMDescription(
        """
        指定された注文をキャンセルする。お客様が明示的に注文のキャンセルを希望した場合にのみ呼ぶこと。
        キャンセル可否や手続きを尋ねているだけの場合は呼ばず、案内に留めること。
        実行後はキャンセル受付の旨を必ずお客様に伝えること。
        """,
    )
    fun cancelOrder(
        @LLMDescription("キャンセルする注文の ID") orderId: String,
    ): String {
        log.info("OrderTools.cancelOrder invoked: orderId={}", orderId)
        return "注文 $orderId のキャンセルを受け付けました。返金がある場合は別途ご案内します。"
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrderTools::class.java)
    }
}
