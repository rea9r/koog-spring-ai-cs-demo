package com.example.csdemo

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import ai.koog.spring.ai.vectorstore.KoogVectorStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SupportGraphService(
    private val promptExecutor: PromptExecutor,
    private val historyProvider: ChatHistoryProvider,
    private val vectorStore: KoogVectorStore,
    private val orderTools: OrderTools,
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
     * A4：classifier 駆動の `when` 分岐を排し、すべての user prompt を tool ループに統一する。
     *
     * before: classify -> when (intent) { ORDER_STATUS -> orderStatusReply; else -> answer }
     * after : answer agent 一本（注文操作は getOrderStatus / cancelOrder tool として LLM に委ねる）
     *
     * Kotlin orchestration から LLM tool dispatch への移行。classifier の境界揺れ（例: A5 で観察した
     * 「ABC3 のキャンセル発話が ORDER_STATUS と誤判定される」現象）が経路選択を破壊しなくなり、
     * tool の `@LLMDescription` が「どのリクエストでどの tool を呼ぶか」の判断基準として機能する。
     *
     * classifier ([classify]) は `/support` endpoint で intent 観察用に残るが、`handle` からは呼ばない。
     */
    suspend fun handle(userPrompt: String, sessionId: String): String =
        answerWithFaqGrounding(userPrompt, sessionId)

    private suspend fun answerWithFaqGrounding(userPrompt: String, sessionId: String): String {
        val augmentedPrompt = augmentWithFaq(userPrompt)
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = OpenAIModels.Chat.GPT5Nano,
            systemPrompt = ANSWER_PROMPT,
            strategy = answerStrategy(),
            toolRegistry = ToolRegistry { tools(orderTools) },
        ) {
            install(ChatMemory.Feature) {
                chatHistoryProvider(historyProvider)
                addPreProcessor(StripFaqContextPreProcessor())
            }
            handleEvents {
                onToolCallStarting { e ->
                    log.info("Tool called: name={} args={}", e.toolName, e.toolArgs)
                }
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
                        summary = "注文 84721 の配送状況を確認したい",
                    ),
                    SupportRequest(
                        intent = SupportIntent.REFUND,
                        orderId = "84721",
                        summary = "注文 84721 の返金を依頼したい",
                    ),
                    SupportRequest(
                        intent = SupportIntent.QUESTION,
                        orderId = null,
                        summary = "返品ポリシーについて知りたい",
                    ),
                    SupportRequest(
                        intent = SupportIntent.QUESTION,
                        orderId = null,
                        summary = "返金にかかる日数を知りたい",
                    ),
                    SupportRequest(
                        intent = SupportIntent.QUESTION,
                        orderId = null,
                        summary = "返金はいつ届くのか目安を知りたい",
                    ),
                    SupportRequest(
                        intent = SupportIntent.QUESTION,
                        orderId = null,
                        summary = "配送日数や送料について知りたい",
                    ),
                    SupportRequest(
                        intent = SupportIntent.OTHER,
                        orderId = "ABC123",
                        summary = "注文 ABC123 のキャンセルを希望",
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
     * tool 呼びを含む answer agent の strategy。
     *
     * `singleRunStrategy()` の中身を自前で展開しつつ、`nodeLLMCompressHistory` を 1 段挟む形:
     *
     * ```
     * Start -> callLLM ──onAssistantMessage→ Finish
     *             │
     *             onToolCall
     *             ↓
     *           executeTool ──onCondition (history が大きい)→ compressHistory ─→ sendToolResult
     *                       └──(それ以外)─────────────────────────────────────────→ sendToolResult
     *                                                                                 │
     *                                                                          onAssistantMessage→ Finish
     *                                                                                 │
     *                                                                          onToolCall→ executeTool（ループ）
     * ```
     *
     * edges は定義順に評価されるため、`compressHistory` 経由の edge を先に書いて、
     * 閾値以下のときだけ直接 sendToolResult に流れる。
     */
    private fun answerStrategy(): AIAgentGraphStrategy<String, String> =
        strategy<String, String>("support_answer_loop") {
            val callLLM by nodeLLMRequest()
            val executeTool by nodeExecuteTool()
            val sendToolResult by nodeLLMSendToolResult()
            val compressHistory by nodeLLMCompressHistory<ReceivedToolResult>(
                strategy = HistoryCompressionStrategy.FromLastNMessages(HISTORY_KEEP_LAST_N),
                preserveMemory = true,
            )

            edge(nodeStart forwardTo callLLM)
            edge(callLLM forwardTo executeTool onToolCall { true })
            edge(callLLM forwardTo nodeFinish onAssistantMessage { true })
            edge(
                executeTool forwardTo compressHistory onCondition { _ ->
                    val size = llm.readSession { prompt.messages.size }
                    val hit = size > HISTORY_COMPRESS_THRESHOLD
                    log.info("History compress check: messages={} threshold={} hit={}", size, HISTORY_COMPRESS_THRESHOLD, hit)
                    hit
                },
            )
            edge(executeTool forwardTo sendToolResult)
            edge(compressHistory forwardTo sendToolResult)
            edge(sendToolResult forwardTo executeTool onToolCall { true })
            edge(sendToolResult forwardTo nodeFinish onAssistantMessage { true })
        }

    companion object {
        private val log = LoggerFactory.getLogger(SupportGraphService::class.java)

        // 学習用 demo として 3-5 turn 目あたりで compression が走るよう低めに設定
        private const val HISTORY_COMPRESS_THRESHOLD = 6
        private const val HISTORY_KEEP_LAST_N = 4

        private val SYSTEM_PROMPT = """
            あなたは EC サイトのカスタマーサポート担当です。簡潔で丁寧に応対してください。
        """.trimIndent()

        /**
         * Step 4-4b: answer agent 用に締めた system prompt。
         * FAQ context が user message に含まれている場合だけ参照させ、
         * 含まれていなければ尋ねられていない policy 詳細を持ち出させない。
         * threshold をすり抜けた無関係な FAQ がぶら下がってきても無視させる節も含む。
         */
        private val ANSWER_PROMPT = """
            あなたは EC サイトのカスタマーサポート担当です。簡潔で丁寧に応対してください。

            お客様のメッセージに「以下の FAQ を参考にしてください:」というセクションが含まれていて、
            その内容がお客様の質問に直接答えるものである場合は、その FAQ だけを根拠にして
            ポリシー（期間、例外など）を回答してください。FAQ に書かれていない具体的な数字や
            条件を勝手に補足しないでください。

            FAQ の項目がお客様の質問と無関係な場合（挨拶やポリシー外の質問など）は、FAQ を無視して
            自然に応対し、ポリシーの話題を持ち出さないでください。

            FAQ セクションが含まれていない場合は、お客様の実際の質問にだけ答え、
            聞かれていないポリシー詳細を自発的に持ち出さないでください。

            操作系・情報取得系のツールが利用可能です:
            - getOrderStatus: 特定の注文 ID の配送状況を取得
            - cancelOrder: 特定の注文 ID をキャンセル
            お客様が明示的に該当の操作・情報取得を希望している場合は、追加で確認を取らずに
            直接ツールを呼び出してください。操作後はツールの実行結果を踏まえてお客様にお伝えしてください。
        """.trimIndent()
    }
}
