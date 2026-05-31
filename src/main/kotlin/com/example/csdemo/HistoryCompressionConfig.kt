package com.example.csdemo

import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Step 5-A6: answer agent の `nodeLLMCompressHistory` 用の strategy / 閾値を設定可能化する。
 *
 * 動機: a5-loop の連投シナリオで圧縮挙動を比較したいが、これまでは
 * `FromLastNMessages(4)` + threshold=6 をコード固定にしていたため、別 strategy を試すには
 * code 変更 + 再起動が必要だった。Mode を application property で切り替えられるようにし、
 * `-Dkoog.support.history-compression.mode=whole` のような property override だけで
 * 戦略比較できるようにする。
 *
 * 利用 strategy（Koog `HistoryCompressionStrategy` の companion factory から取得）:
 * - [Mode.NONE]: 圧縮しない（`NoCompression`）。閾値超えても元 history のまま LLM に渡る
 * - [Mode.WHOLE]: 全 history を 1 つの TLDR summary に圧縮（`WholeHistory`）
 * - [Mode.FROM_LAST_N]: 直近 N 件を残し、残りを TLDR に圧縮（`FromLastNMessages(keepLastN)`）
 * - [Mode.CHUNKED]: chunkSize ごとに分割して各 chunk を TLDR に圧縮（`Chunked(chunkSize)`）
 *
 * [threshold] は edge の `onCondition` で参照する prompt.messages.size の閾値。
 * NONE モードでは閾値判定自体が無意味だが、log の hit/false 表示のために残す。
 */
@ConfigurationProperties(prefix = "koog.support.history-compression")
data class HistoryCompressionConfig(
    val mode: Mode = Mode.FROM_LAST_N,
    val keepLastN: Int = 4,
    val chunkSize: Int = 4,
    val threshold: Int = 6,
) {
    enum class Mode { NONE, WHOLE, FROM_LAST_N, CHUNKED }

    fun resolveStrategy(): HistoryCompressionStrategy = when (mode) {
        Mode.NONE -> HistoryCompressionStrategy.NoCompression
        Mode.WHOLE -> HistoryCompressionStrategy.WholeHistory
        Mode.FROM_LAST_N -> HistoryCompressionStrategy.FromLastNMessages(keepLastN)
        Mode.CHUNKED -> HistoryCompressionStrategy.Chunked(chunkSize)
    }
}
