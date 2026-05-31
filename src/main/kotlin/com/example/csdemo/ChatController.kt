package com.example.csdemo

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Step 1: smoke test.
 *
 * Injects the [PromptExecutor] bean that the Koog Spring AI chat starter
 * auto-configures from the existing Spring AI OpenAI setup, then runs a
 * one-shot agent. This is the smallest "it talks to the LLM" wiring; the
 * graph strategy, RAG, memory and persistence come in later steps.
 */
@RestController
class ChatController(
	private val promptExecutor: PromptExecutor,
) {
	@PostMapping("/chat")
	fun chat(@RequestBody request: ChatRequest): ChatResponse = runBlocking {
		val agent = AIAgent(
			promptExecutor = promptExecutor,
			llmModel = OpenAIModels.Chat.GPT5Nano,
			systemPrompt = "You are a helpful customer support assistant. Answer concisely.",
			strategy = singleRunStrategy(),
			toolRegistry = ToolRegistry.EMPTY,
		)
		ChatResponse(agent.run(request.prompt))
	}
}

data class ChatRequest(val prompt: String)

data class ChatResponse(val response: String)
