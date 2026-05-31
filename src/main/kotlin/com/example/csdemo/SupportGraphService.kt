package com.example.csdemo

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.spring.ai.vectorstore.KoogVectorStore
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
     * Step 2-2 / 3 / 4：問い合わせを intent で分岐して回答する。sessionId 単位で会話履歴を引き継ぐ。
     * FAQ 取得は [routingStrategy] 内の `retrieveContext` ノードで非 ORDER_STATUS 経路のみに適用される。
     */
    suspend fun handle(userPrompt: String, sessionId: String): String {
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = OpenAIModels.Chat.GPT5Nano,
            systemPrompt = SYSTEM_PROMPT,
            strategy = routingStrategy(),
            toolRegistry = ToolRegistry.EMPTY,
        ) {
            install(ChatMemory.Feature) {
                chatHistoryProvider(historyProvider)
            }
        }
        return agent.run(userPrompt, sessionId)
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

    /**
     * 分類結果の intent によって応答経路を切り替えるグラフ。
     *
     * ```
     * Start
     *  └─ classify (LLM 構造化出力)
     *       ├─ isFailure -> Finish ("Sorry, ...")
     *       └─ isSuccess -> extractRequest (identity)
     *             ├─ intent == ORDER_STATUS -> orderStatusFlow (固定文を返す subgraph) -> Finish
     *             └─ otherwise               -> retrieveContext (FAQ grounding) -> generalAnswer -> Finish
     * ```
     *
     * `extractRequest` は identity ノード。`classify` の出力は `Result<...>` 型なので、
     * intent で条件分岐するには先に `Result` を剥がして [SupportRequest] 本体を取り出す必要がある。
     *
     * Step 4-3: 非 ORDER_STATUS 経路のみ `retrieveContext` を通して FAQ を検索し、
     * 取得結果を user message に prepend したうえで LLM に渡す。LongTermMemory feature の
     * automatic retrieval は使わない（classifier ノードに retrieval が掛かるのを避けるため）。
     */
    private fun routingStrategy(): AIAgentGraphStrategy<String, String> =
        strategy<String, String>("support_routing") {
            val classify by nodeLLMRequestStructured<SupportRequest>(
                examples = listOf(
                    SupportRequest(
                        intent = SupportIntent.ORDER_STATUS,
                        orderId = "84721",
                        summary = "Check the status of order 84721",
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

            val extractRequest by node<SupportRequest, SupportRequest> { it }

            val orderStatusFlow by subgraph<SupportRequest, String>(name = "order_status_flow") {
                val answer by node<SupportRequest, String> { orderStatusReply(it) }
                nodeStart then answer then nodeFinish
            }

            val retrieveContext by node<String, String> { query ->
                val results = vectorStore.search(
                    SimilaritySearchRequest(
                        queryText = query,
                        limit = 3,
                        minScore = 0.5,
                    ),
                )
                if (results.isEmpty()) {
                    query
                } else {
                    buildString {
                        appendLine("Reference the following FAQ items if relevant:")
                        results.forEach { appendLine("- ${it.document.content}") }
                        appendLine()
                        append("Customer question: $query")
                    }
                }
            }

            val generalAnswer by nodeLLMRequest()

            // 分類実行
            edge(nodeStart forwardTo classify)

            // 分類の成功/失敗で分岐（失敗は早期 Finish）
            edge(classify forwardTo extractRequest onCondition { it.isSuccess } transformed { it.getOrThrow().data })
            edge(
                classify forwardTo nodeFinish onCondition { it.isFailure } transformed {
                    "Sorry, I couldn't understand your request. Please rephrase it."
                },
            )

            // intent で分岐
            edge(extractRequest forwardTo orderStatusFlow onCondition { it.intent == SupportIntent.ORDER_STATUS })
            edge(extractRequest forwardTo retrieveContext onCondition { it.intent != SupportIntent.ORDER_STATUS } transformed { it.summary })

            // 各経路から Finish へ
            edge(retrieveContext forwardTo generalAnswer)
            edge(orderStatusFlow forwardTo nodeFinish)
            edge(generalAnswer forwardTo nodeFinish transformed { it.content })
        }

    companion object {
        private val SYSTEM_PROMPT = """
            You are an e-commerce customer support assistant. Be helpful, concise, and polite.
        """.trimIndent()
    }
}
