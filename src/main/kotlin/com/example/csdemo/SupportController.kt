package com.example.csdemo

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Step 2-1：POST /support は分類結果 [SupportRequest] を JSON で返す。
 */
@RestController
class SupportController(
	private val supportGraphService: SupportGraphService,
) {
	@PostMapping("/support")
	fun support(@RequestBody request: SupportQuery): SupportRequest = runBlocking {
		supportGraphService.classify(request.prompt)
	}
}

data class SupportQuery(val prompt: String)
