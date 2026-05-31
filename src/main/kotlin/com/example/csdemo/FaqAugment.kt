package com.example.csdemo

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message

/**
 * FAQ-grounded prompt の format を一元化する。
 *
 * - [HEADER] と [QUESTION_MARKER] は augmented user message の固定マーカ
 * - [build] が augmented prompt 文字列を組み立てる
 * - [StripFaqContextPreProcessor] が ChatMemory store 直前にマーカを頼りに剥がす
 *
 * 同じ marker 文字列がコード上の複数箇所に散ると preprocessor と augment の整合が崩れやすいので、
 * ここに集約している。
 */
internal object FaqAugment {
    const val HEADER: String = "以下の FAQ を参考にしてください:"
    const val QUESTION_MARKER: String = "お客様の質問: "

    fun build(query: String, faqs: List<String>): String =
        if (faqs.isEmpty()) {
            query
        } else {
            buildString {
                appendLine(HEADER)
                faqs.forEach { appendLine("- $it") }
                appendLine()
                append(QUESTION_MARKER)
                append(query)
            }
        }
}

/**
 * Step 4-4c：ChatMemory に store される直前の user message から [FaqAugment] が prepend した
 * FAQ ブロックを剥がす。
 *
 * `agent.run(augmentedPrompt, sessionId)` で渡した文字列はそのまま user role の Message として
 * ChatHistoryProvider に保存される。次ターンの履歴で replay されると LLM が "the FAQ you shared..."
 * のような stale な FAQ snippet 由来の誤参照をするため、store する手前で original query 部分に
 * 戻している。
 *
 * preprocessors は load 時にも走るが、store 値が既に clean なら load 時の処理は no-op になる
 * （idempotent）。既存の polluted な履歴も load 時に自動 clean 化される。
 */
internal class StripFaqContextPreProcessor : ChatMemoryPreProcessor {
    override fun preprocess(messages: List<Message>): List<Message> = messages.map { msg ->
        if (msg is Message.User && msg.content.startsWith(FaqAugment.HEADER)) {
            val original = msg.content.substringAfterLast(FaqAugment.QUESTION_MARKER, "").trim()
            if (original.isNotEmpty()) {
                Message.User(content = original, metaInfo = msg.metaInfo, cacheControl = msg.cacheControl)
            } else {
                msg
            }
        } else {
            msg
        }
    }
}
