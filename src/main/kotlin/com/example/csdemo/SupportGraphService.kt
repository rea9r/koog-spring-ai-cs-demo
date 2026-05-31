package com.example.csdemo

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

internal fun orderStatusReply(request: SupportRequest): String =
    "Your order ${request.orderId ?: "unknown"} is being processed and will ship soon."

@Service
class SupportGraphService(
    private val promptExecutor: PromptExecutor,
    private val historyProvider: ChatHistoryProvider,
    private val vectorStore: KoogVectorStore,
) {

    /**
     * Step 2-1：問い合わせ文を型付きの [SupportRequest] に分類する。
     */
    suspend fun classify(userPrompt: String): SupportRequest {
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = OpenAIModels.Chat.GPT5Nano,
            systemPrompt = SYSTEM_PROMPT,
            strategy = classifyStrategy(),
            toolRegistry = ToolRegistry.EMPTY,
        )
        return agent.run(userPrompt)
    }

    /**
     * Step 4-4：classifier と answer を別 agent に分離する。
     * - classifier ([classify]) は ChatMemory なし。`/support` でも再利用される
     * - ORDER_STATUS は LLM を呼ばずに [orderStatusReply] を直接返す
     * - その他は FAQ 検索結果を user message に prepend したうえで、ChatMemory 付き answer agent に投げる
     *
     * これにより ChatMemory は answer 経路だけに効き、classifier の判定が会話履歴で揺れない。
     */
    suspend fun handle(userPrompt: String, sessionId: String): String {
        val request = classify(userPrompt)
        return when (request.intent) {
            SupportIntent.ORDER_STATUS -> orderStatusReply(request)
            else -> answerWithFaqGrounding(userPrompt, sessionId)
        }
    }

    private suspend fun answerWithFaqGrounding(userPrompt: String, sessionId: String): String {
        val augmentedPrompt = augmentWithFaq(userPrompt)
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = OpenAIModels.Chat.GPT5Nano,
            systemPrompt = ANSWER_PROMPT,
            strategy = singleRunStrategy(),
            toolRegistry = ToolRegistry.EMPTY,
        ) {
            install(ChatMemory.Feature) {
                chatHistoryProvider(historyProvider)
                addPreProcessor(StripFaqContextPreProcessor())
            }
        }
        return agent.run(augmentedPrompt, sessionId)
    }

    private suspend fun augmentWithFaq(query: String): String {
        val results = vectorStore.search(
            SimilaritySearchRequest(
                queryText = query,
                limit = 3,
                minScore = 0.5,
            ),
        )
        log.info(
            "FAQ retrieval: query='{}' hits={} scores={}",
            query.take(60),
            results.size,
            results.map { "%.3f".format(it.score.value) },
        )
        return FaqAugment.build(query, results.map { it.document.content })
    }

    /**
     * 入力テキストを [SupportRequest] 型に構造化分類するだけの一直線グラフ。
     *
     * ```
     * Start -> classify (LLM 構造化出力) -> Finish
     * ```
     *
     * [nodeLLMRequestStructured] の出力は `Result<Structured<SupportRequest>>` なので、
     * Finish に渡す前に `getOrThrow().data` で SupportRequest 本体を取り出している。
     */
    private fun classifyStrategy(): AIAgentGraphStrategy<String, SupportRequest> =
        strategy<String, SupportRequest>("support_intent_classifier") {
            val classify by nodeLLMRequestStructured<SupportRequest>(
                examples = listOf(
                    SupportRequest(
                        intent = SupportIntent.ORDER_STATUS,
                        orderId = "84721",
                        summary = "Check the status of order 84721",
                    ),
                    SupportRequest(
                        intent = SupportIntent.REFUND,
                        orderId = "84721",
                        summary = "Request a refund for order 84721",
                    ),
                    SupportRequest(
                        intent = SupportIntent.QUESTION,
                        orderId = null,
                        summary = "Ask about the return policy",
                    ),
                ),
                fixingParser = StructureFixingParser(
                    model = OpenAIModels.Chat.GPT5Nano,
                    retries = 2,
                ),
            )

            edge(nodeStart forwardTo classify)
            edge(classify forwardTo nodeFinish transformed { it.getOrThrow().data })
        }

    companion object {
        private val log = LoggerFactory.getLogger(SupportGraphService::class.java)

        private val SYSTEM_PROMPT = """
            You are an e-commerce customer support assistant. Be helpful, concise, and polite.
        """.trimIndent()

        /**
         * Step 4-4b: answer agent 用に締めた system prompt。
         * FAQ context が user message に含まれている場合だけ参照させ、
         * 含まれていなければ尋ねられていない policy 詳細を持ち出させない。
         * threshold をすり抜けた無関係な FAQ がぶら下がってきても無視させる節も含む。
         */
        private val ANSWER_PROMPT = """
            You are an e-commerce customer support assistant. Be helpful, concise, and polite.

            When the user message includes a "Reference the following FAQ items" section
            AND those items address the customer's actual question, treat those items as
            your only source for company policy facts (timeframes, exceptions, etc.).
            Do not invent details beyond what is listed.

            If the listed FAQ items are unrelated to the customer's actual question
            (e.g., they greeted you, or asked something off-policy), ignore the items
            and respond naturally without bringing up policy.

            When no FAQ section is provided, answer the customer's actual question and do
            not volunteer unrelated policy details.
        """.trimIndent()
    }
}
