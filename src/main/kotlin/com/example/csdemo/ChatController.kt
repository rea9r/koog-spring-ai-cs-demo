package com.example.csdemo

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStreaming
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Step 1：PromptExecutor を使って LLM に1往復だけ問い合わせる最小エージェント。
 * Step 5-A8 (A1)：streaming 版 `/chat/stream` を追加。
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

	/**
	 * Step 5-A8 (A1): Koog の `nodeLLMRequestStreaming` で `Flow<StreamFrame>` を取得し、
	 * Spring MVC の [SseEmitter] にブリッジして SSE で逐次返す。
	 *
	 * - strategy の Output 型を `Flow<StreamFrame>` にして AIAgent から flow を返す
	 * - StreamFrame は `@Serializable` sealed interface なので `Json.encodeToString` で JSON 化
	 * - tool ループなし（A1 の最小実装、tool ループとの組み合わせは別 trip）
	 * - WebMVC なので `SseEmitter` を使う（WebFlux なら `Flux<ServerSentEvent>` が筋）
	 */
	@PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun chatStream(@RequestBody request: ChatRequest): SseEmitter {
		val emitter = SseEmitter(0L)
		val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

		scope.launch {
			try {
				val agent = AIAgent(
					promptExecutor = promptExecutor,
					llmModel = OpenAIModels.Chat.GPT5Nano,
					systemPrompt = "You are a helpful customer support assistant. Answer concisely.",
					strategy = streamingStrategy(),
					toolRegistry = ToolRegistry.EMPTY,
				)
				val flow: Flow<StreamFrame> = agent.run(request.prompt)
				flow.collect { frame ->
					emitter.send(SseEmitter.event().data(streamFrameJson.encodeToString(frame)).build())
				}
				emitter.complete()
			} catch (e: Exception) {
				log.error("Stream failed", e)
				emitter.completeWithError(e)
			}
		}
		return emitter
	}

	private fun streamingStrategy(): AIAgentGraphStrategy<String, Flow<StreamFrame>> =
		strategy<String, Flow<StreamFrame>>("chat_stream") {
			val stream by nodeLLMRequestStreaming()
			edge(nodeStart forwardTo stream)
			edge(stream forwardTo nodeFinish)
		}

	private companion object {
		private val log = LoggerFactory.getLogger(ChatController::class.java)
		private val streamFrameJson = Json { encodeDefaults = true }
	}
}

data class ChatRequest(val prompt: String)

data class ChatResponse(val response: String)
