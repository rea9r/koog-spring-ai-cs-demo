package com.example.csdemo

import ai.koog.spring.ai.vectorstore.KoogVectorStore
import ai.koog.spring.ai.vectorstore.SpringAiKoogVectorStore
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
     * Step 5-A13 (A16): LongTermMemory 用に独立した PgVectorStore Bean を作る。
     *
     * 学び 54 で発覚した「FAQ retrieval と LTM ingestion を同じ vectorStore で共有すると相互汚染が
     * 発生する」問題への対処。`vectorTableName` を `ltm_records` に明示指定して別 table に隔離する。
     * 既存の FAQ 用 [vectorStore] は default の `vector_store` table 名のまま残す。
     */
    @Bean
    fun ltmPgVectorStore(
        jdbcTemplate: JdbcTemplate,
        embeddingModel: EmbeddingModel,
    ): PgVectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .vectorTableName("ltm_records")
        .dimensions(EMBEDDING_DIMENSIONS)
        .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
        .indexType(PgVectorStore.PgIndexType.HNSW)
        .initializeSchema(true)
        .build()

    /**
     * Step 5-A13 (A16): FAQ 用 + LTM 用の KoogVectorStore Bean を両方手動で定義する。
     *
     * Koog autoconfig の `@ConditionalOnMissingBean(KoogVectorStore::class)` は型ベース判定なので、
     * 手動で `ltmKoogVectorStore` (KoogVectorStore 型) を 1 つでも登録すると autoconfig 側の
     * KoogVectorStore Bean 生成が一切スキップされてしまう。結果として
     * `@Qualifier("springAiKoogVectorStore")` の resolution が失敗する。
     *
     * 対処として autoconfig に頼らず、FAQ 用も自前で `SpringAiKoogVectorStore` をラップする
     * Bean を定義する。`application.yml` の `vector-store-bean-name` も不要になる。
     */
    @Bean
    fun faqKoogVectorStore(
        @Qualifier("vectorStore") store: PgVectorStore,
        @Qualifier("koogSpringAiVectorStoreDispatcher") dispatcher: CoroutineDispatcher,
    ): KoogVectorStore = SpringAiKoogVectorStore(store, dispatcher)

    @Bean
    fun ltmKoogVectorStore(
        @Qualifier("ltmPgVectorStore") store: PgVectorStore,
        @Qualifier("koogSpringAiVectorStoreDispatcher") dispatcher: CoroutineDispatcher,
    ): KoogVectorStore = SpringAiKoogVectorStore(store, dispatcher)

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
