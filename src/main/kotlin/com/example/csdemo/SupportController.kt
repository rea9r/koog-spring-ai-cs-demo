package com.example.csdemo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Step 2：EC のカスタマーサポート問い合わせを Koog グラフで分類し、intent に応じて応答するコントローラ。
 * Step 5-A9 (A11)：tool ループ込みの streaming 版 `/support/handle/stream` を追加。
 */
@RestController
class SupportController(
	private val supportGraphService: SupportGraphService,
) {
	/**
	 * Step 5-B8 (B9): LLM の summary 生成を排して、user prompt を rule-based に response に含める。
	 * SupportRequest (LLM 出力) + 入力 prompt を controller layer で merge して
	 * [SupportClassificationResponse] として返す。
	 */
	@PostMapping("/support")
	fun support(@RequestBody request: SupportQuery): SupportClassificationResponse = runBlocking {
		val classification = supportGraphService.classify(request.prompt)
		SupportClassificationResponse(
			intent = classification.intent,
			orderId = classification.orderId,
			userPrompt = request.prompt.take(USER_PROMPT_RESPONSE_LIMIT),
		)
	}

	@PostMapping("/support/handle")
	fun handle(@RequestBody request: SupportHandleQuery): SupportResponse = runBlocking {
		SupportResponse(supportGraphService.handle(request.prompt, request.sessionId))
	}

	/**
	 * Step 5-A10 (A2): LongTermMemory feature を併設した handle。
	 * ChatMemory との棲み分け観察用。
	 */
	@PostMapping("/support/handle/ltm")
	fun handleLtm(@RequestBody request: SupportHandleQuery): SupportResponse = runBlocking {
		SupportResponse(supportGraphService.handleWithLtm(request.prompt, request.sessionId))
	}

	/**
	 * Step 5-A9 (A11): tool ループ込みの streaming 版。
	 *
	 * `SupportGraphService.handleStream` の callback で受け取った各 `StreamFrame` を JSON にして
	 * SSE event data に乗せる。tool 呼びがある場合、ToolCallDelta / ToolCallComplete も同じ stream に
	 * 並んで配信される（観察ポイント）。最後に final response (tool ループ完了後の最終 assistant 応答) を
	 * `response` event として送り、emitter を close する。
	 */
	@PostMapping("/support/handle/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun handleStream(@RequestBody request: SupportHandleQuery): SseEmitter {
		val emitter = SseEmitter(0L)
		val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

		scope.launch {
			try {
				val finalResponse = supportGraphService.handleStream(request.prompt, request.sessionId) { frame ->
					emitter.send(SseEmitter.event().data(streamFrameJson.encodeToString(frame)).build())
				}
				emitter.send(SseEmitter.event().name("response").data(SupportResponse(finalResponse)).build())
				emitter.complete()
			} catch (e: Exception) {
				log.error("Stream failed", e)
				emitter.completeWithError(e)
			}
		}
		return emitter
	}

	private companion object {
		private val log = LoggerFactory.getLogger(SupportController::class.java)
		private val streamFrameJson = Json { encodeDefaults = true }
		private const val USER_PROMPT_RESPONSE_LIMIT = 200
	}
}

data class SupportQuery(val prompt: String)

data class SupportHandleQuery(val prompt: String, val sessionId: String)

data class SupportResponse(val response: String)
