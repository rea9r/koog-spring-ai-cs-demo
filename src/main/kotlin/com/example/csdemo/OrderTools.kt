package com.example.csdemo

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
@LLMDescription("注文に関する操作を行うツール群。お客様の明示的な依頼に応じて呼ぶこと。")
class OrderTools(
    private val store: CancelledOrderStore,
) : ToolSet {

    @Tool
    @LLMDescription(
        """
        指定された注文をキャンセルする。お客様が明示的に注文のキャンセルを希望した場合にのみ呼ぶこと。
        キャンセル可否や手続きを尋ねているだけの場合は呼ばず、案内に留めること。
        実行後はツールの応答内容（新規受付 / 既に処理済み）をそのままお客様に伝えること。
        """,
    )
    fun cancelOrder(
        @LLMDescription("キャンセルする注文の ID") orderId: String,
    ): String = when (val result = store.cancel(orderId)) {
        is CancelResult.Accepted -> {
            log.info("OrderTools.cancelOrder accepted: orderId={} cancelledAt={}", orderId, result.cancelledAt)
            "注文 $orderId のキャンセルを受け付けました（${result.cancelledAt}）。返金がある場合は別途ご案内します。"
        }
        is CancelResult.AlreadyCancelled -> {
            log.info("OrderTools.cancelOrder already-cancelled: orderId={} cancelledAt={}", orderId, result.cancelledAt)
            "注文 $orderId は既に ${result.cancelledAt} にキャンセル受付済みです。再度の手続きは不要です。"
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OrderTools::class.java)
    }
}
