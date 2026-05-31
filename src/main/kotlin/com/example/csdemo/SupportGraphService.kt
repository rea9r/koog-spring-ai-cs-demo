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
    "ご注文 ${request.orderId ?: "不明"} は現在処理中で、まもなく発送されます。"

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
        """.trimIndent()
    }
}
