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
     * A4 で classifier 駆動の `when` 分岐を排し、A7 で tool 経路の FAQ retrieval skip を追加。
     *
     * 流れ:
     * 1. [looksLikeOrderOperation] でヒューリスティック判定（注文キーワード + ID 風文字列の同居）
     * 2. true -> FAQ retrieval を skip して prompt をそのまま answer agent に渡す
     * 3. false -> 従来通り [augmentWithFaq] で FAQ context を prepend
     * 4. answer agent (tool dispatch + ChatMemory) が応答生成
     *
     * A7 の動機: B1 (b1-s1) で観察された「tool 経路でも FAQ retrieval が走り、LLM が補足展開する」
     * 課題を経路設計側で防ぐ。prompt engineering の限界に対する構造的対処。
     *
     * classifier ([classify]) は `/support` endpoint 用に残るが、`handle` からは呼ばない。
     */
    suspend fun handle(userPrompt: String, sessionId: String): String {
        val skipFaq = looksLikeOrderOperation(userPrompt)
        log.info("Routing decision: skipFaqRetrieval={} prompt='{}'", skipFaq, userPrompt.take(60))
        val input = if (skipFaq) userPrompt else augmentWithFaq(userPrompt)
        return runAnswerAgent(input, sessionId)
    }

    private suspend fun runAnswerAgent(input: String, sessionId: String): String {
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
        return agent.run(input, sessionId)
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
         * B1: 3 セクション構造に再編成した answer agent 用 system prompt。
         *
         * A5/A4 で観察された 3 課題に対処:
         * 1. retrieval miss 時の hallucination -> FAQ がない/答えがない場合は数字・条件を推測しない
         * 2. tool return 不忠実伝達 (A5 s2: 「既にキャンセル受付済み」を「キャンセル処理を実行しました」と演出)
         *    -> tool 結果の文言を主とし、新規実行風に言い換えない
         * 3. tool 経路での FAQ 過剰補足 (A4 a4-mix: getOrderStatus 経路で「配送状況の確認方法」等を展開)
         *    -> tool で完結する質問には FAQ 補足を加えない
         */
        private val ANSWER_PROMPT = """
            あなたは EC サイトのカスタマーサポート担当です。簡潔で丁寧に応対してください。

            # FAQ context の扱い
            お客様のメッセージに「以下の FAQ を参考にしてください:」セクションが含まれる場合:
            - 質問に直接答える FAQ があれば、その FAQ だけを根拠に回答する（期間、例外など）
            - FAQ に書かれていない具体的な数字や条件を推測で補足しない
            - FAQ が質問と無関係なら（挨拶、ポリシー外の質問など）FAQ を無視して自然に応対する

            FAQ セクションがない、または FAQ に直接の答えがない場合は、推測で日数・金額・条件を
            答えず、「個別にお調べします」「サポートまでご連絡ください」のような案内に留めること。

            # ツール利用
            - getOrderStatus: 特定の注文 ID の配送状況を取得
            - cancelOrder: 特定の注文 ID をキャンセル
            お客様が明示的に該当の操作・情報取得を希望していれば、追加で確認を取らずに直接呼び出す。

            # ツール実行後の応答ルール（重要）
            - ツール結果を「主」とし、ツール結果に書かれた事実だけをお客様に伝える
            - ツールが返した文言（例: 「既にキャンセル受付済み」）を新規実行風に言い換えない
            - ツールで完結する質問には FAQ からの補足展開（確認方法、配送日数の目安など）を勝手に追加しない
            - 補足するのは、ツールでは答えられない一般的な質問が同じ発話に含まれる場合のみ
        """.trimIndent()
    }
}
