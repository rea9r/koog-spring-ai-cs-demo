package com.example.csdemo

/**
 * Step 5-A7: prompt が注文操作（getOrderStatus / cancelOrder）を期待しているかを軽量判定する。
 *
 * 判定条件: 「注文」「オーダー」「order」のいずれかキーワードと、ID 風文字列（英大文字 1+ 文字 + 数字、
 * または数字 3+ 桁）が同じ prompt 内に同居している。
 *
 * Step 5-A15 (A9) で regex を緩めた:
 * - before: `[A-Z]{2,}\d+ | \d{4,}` → P1 / ABC9 / N1 のような単短 ID が外れていた (学び 38)
 * - after : `[A-Z]+\d+ | \d{3,}` → P1 や 84721 はマッチ、「オーダーNo. 12」のような短数字は false 維持
 *
 * 動機: B1 で課題 3（tool 経路の FAQ 過剰補足）が prompt engineering だけでは抑制しきれなかった
 * (b1-s1 で `getOrderStatus(84721)` 経路に「マイページ / メール」FAQ 補足が残った)。
 * tool 呼びが期待される prompt では FAQ retrieval 自体を skip して、prompt に FAQ context を
 * prepend しない構造で完全抑制する。
 *
 * 限界: ヒューリスティックなので網羅性に限界がある。"my order ABC123" のような英語の "ABC123" は
 * 拾えるが文脈的な英語 prompt は別途検査が要る。ULID 系の新形式 ID も部分マッチに留まる。
 * production では軽量 classifier or NER に振り替える前提。
 */
internal fun looksLikeOrderOperation(prompt: String): Boolean {
    val containsOrderKeyword = ORDER_KEYWORDS.any { prompt.contains(it, ignoreCase = true) }
    val containsOrderIdPattern = ORDER_ID_PATTERN.containsMatchIn(prompt)
    return containsOrderKeyword && containsOrderIdPattern
}

private val ORDER_KEYWORDS = listOf("注文", "オーダー", "order")
private val ORDER_ID_PATTERN = Regex("""[A-Z]+\d+|\d{3,}""")
