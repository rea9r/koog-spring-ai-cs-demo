package com.example.csdemo

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Step 4 (任意)：FAQ grounding 用の VectorStore を PgVector に差し替える。
 *
 * Step 4-1 までは Spring AI 同梱の [org.springframework.ai.vectorstore.SimpleVectorStore]
 * （in-memory）を使っていた。本ステップでは pgvector 拡張入りの Postgres を `docker compose up -d` で
 * 立てて、Bean を [PgVectorStore] に置き換えるだけで他のコードを触らずに動くことを確認する。
 *
 * 注意：[PgVectorStore] は schema 初期化を [PgVectorStore.afterPropertiesSet] で行うので、
 * `@Bean` factory 内で `add()` を呼ぶと table が存在しないまま SQL が走ってしまう。
 * seed は [ApplicationRunner] で context 起動後に流すことで Spring lifecycle を尊重する。
 */
@Configuration
class VectorStoreConfig {

    /**
     * Spring AI の [org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration]
     * も `vectorStore` という名前で [PgVectorStore] を提供しようとする。auto-config 側の
     * `@ConditionalOnMissingBean` は本メソッドの返り値型で判定するため、ここを [VectorStore] interface に
     * 落とすと「PgVectorStore Bean は無い」と判定されて Bean 名衝突で起動が失敗する。
     * 具象 [PgVectorStore] を返り値型に明示することで auto-config の duplicate 定義を抑制している。
     */
    @Bean
    fun vectorStore(
        jdbcTemplate: JdbcTemplate,
        embeddingModel: EmbeddingModel,
    ): PgVectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .dimensions(EMBEDDING_DIMENSIONS)
        .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
        .indexType(PgVectorStore.PgIndexType.HNSW)
        .initializeSchema(true)
        .build()

    /**
     * 起動時に FAQ が空なら seed する。再起動時は既存データを保持。
     * `similaritySearch` を 1 回投げて空判定するので、起動のたびに embedding API を 1 回叩く。
     */
    @Bean
    fun faqSeeder(vectorStore: VectorStore): ApplicationRunner = ApplicationRunner {
        val probe = vectorStore.similaritySearch(
            SearchRequest.builder().query("seed-check").topK(1).build(),
        )
        if (probe.isNullOrEmpty()) {
            val docs = seedFaqDocuments()
            vectorStore.add(docs)
            log.info("Seeded {} FAQ documents into the VectorStore", docs.size)
        } else {
            log.info("VectorStore already contains data; skipping FAQ seed")
        }
    }

    private fun seedFaqDocuments(): List<Document> = listOf(
        "返品ポリシー: 商品お届け後 30 日以内であれば理由を問わず返品を受け付けます。未使用かつ元の梱包の状態でお戻しください。",
        "返金処理: 返品商品を当社で受領後、5〜7 営業日以内に返金を処理します。返金はご購入時の決済方法に対して行われます。",
        "配送日数: 通常配送は 3〜5 営業日でお届けします。お急ぎ便（追加料金）は 1〜2 営業日です。15 時以降のご注文は翌営業日の発送となります。",
        "注文の追跡: マイページの注文詳細から、または発送完了メールに記載のトラッキングリンクから、配送状況をご確認いただけます。",
        "注文のキャンセル: 出荷準備に入る前であれば手数料なしでキャンセル可能です。出荷後の場合は返品手続きとしてご対応ください。",
        "破損品の対応: 商品が破損して到着した場合は、お受け取りから 7 日以内に写真を添えてサポートまでご連絡ください。無償交換または全額返金で対応します。",
    ).map { Document.builder().text(it).build() }

    private companion object {
        private val log = LoggerFactory.getLogger(VectorStoreConfig::class.java)

        // text-embedding-3-small が返すベクトルの次元数
        private const val EMBEDDING_DIMENSIONS = 1536
    }
}
