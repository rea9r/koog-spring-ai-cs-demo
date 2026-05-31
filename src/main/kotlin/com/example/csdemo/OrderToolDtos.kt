package com.example.csdemo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Step 5-A10: OrderTools の tool 戻りを構造化 JSON で表現するための DTO。
 *
 * 動機: A8 で WindowSizePreProcessor を入れて pattern 引きずりは解消したが、学び 29「具体例を
 * 含む明示は LLM behavior を変えるトリガーとして効きやすい」を逆方向に活用し、tool I/O contract
 * そのものを JSON 化することで LLM の解釈ぶれを更に狭める。
 *
 * tool は String しか戻せないため、[OrderToolJson] で文字列化して return する。`ANSWER_PROMPT`
 * 側で「JSON の status フィールドに基づいて応答」を明示し、LLM が「自然文として再構成する」のではなく
 * 「JSON parse して日本語化する」流れに誘導する。
 */
@Serializable
data class CancelOrderResult(
    val status: String, // "accepted" | "already_cancelled"
    val orderId: String,
    val cancelledAt: String,
)

@Serializable
data class GetOrderStatusResult(
    val orderId: String,
    val status: String, // demo 範囲では "processing" 固定
    val note: String? = null,
)

internal val OrderToolJson: Json = Json {
    encodeDefaults = true
    prettyPrint = false
}
