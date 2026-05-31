package com.example.csdemo

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Step 2：EC のカスタマーサポート問い合わせを Koog グラフで分類し、intent に応じて応答するコントローラ。
 */
@RestController
class SupportController(
	private val supportGraphService: SupportGraphService,
) {
	@PostMapping("/support")
	fun support(@RequestBody request: SupportQuery): SupportRequest = runBlocking {
		supportGraphService.classify(request.prompt)
	}

	@PostMapping("/support/handle")
	fun handle(@RequestBody request: SupportHandleQuery): SupportResponse = runBlocking {
		SupportResponse(supportGraphService.handle(request.prompt, request.sessionId))
	}
}

data class SupportQuery(val prompt: String)

data class SupportHandleQuery(val prompt: String, val sessionId: String)

data class SupportResponse(val response: String)
