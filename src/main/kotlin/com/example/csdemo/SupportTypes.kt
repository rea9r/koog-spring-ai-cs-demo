package com.example.csdemo

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Step 2-1：LLM に分類させるための構造化出力の型。
 */
@Serializable
@SerialName("SupportIntent")
enum class SupportIntent {
	ORDER_STATUS,
	CHANGE_ADDRESS,
	REFUND,
	QUESTION,
	OTHER,
}

@Serializable
@LLMDescription("Normalized support request extracted from a user message.")
data class SupportRequest(
	@property:LLMDescription("Detected support intent")
	val intent: SupportIntent,

	@property:LLMDescription("Order ID if mentioned by the user, otherwise null")
	val orderId: String? = null,

	@property:LLMDescription("Short one-sentence summary of what the user wants")
	val summary: String,
)
