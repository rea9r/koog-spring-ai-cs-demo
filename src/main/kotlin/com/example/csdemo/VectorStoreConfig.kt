package com.example.csdemo

import ai.koog.spring.ai.vectorstore.KoogVectorStore
import ai.koog.spring.ai.vectorstore.SpringAiKoogVectorStore
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
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
     *
     * Step 5-B3 (B2 trip): 以前は `vectorStore.similaritySearch(query="seed-check")` の空判定で
     * 制御していたが、空 DB でも非空 list が返るケースが観察された (b2-s1〜s4 検証時に
     * `VectorStore already contains data; skipping FAQ seed` ログが出ているのに `SELECT COUNT(*)`
     * は 0 行のミスマッチ)。`JdbcTemplate` で直接 row count を見る形に変えて idempotent + 確実に。
     * 副次的に: 起動時の embedding API call も 1 回減る。
     */
    @Bean
    fun faqSeeder(
        @Qualifier("vectorStore") vectorStore: VectorStore,
        jdbcTemplate: JdbcTemplate,
    ): ApplicationRunner = ApplicationRunner {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store",
            Long::class.java,
        ) ?: 0L
        if (count == 0L) {
            val docs = seedFaqDocuments()
            vectorStore.add(docs)
            log.info("Seeded {} FAQ documents into the VectorStore", docs.size)
        } else {
            log.info("VectorStore already contains {} rows; skipping FAQ seed", count)
        }
    }

    /**
     * Step 5-B3 (B2): 1 概念 = 1 doc に細分化し、retrieval の境界判定を効きやすくする。
     *
     * 動機 (学び 30): B1 で観察された「破損品の返金処理時間」query が「返金処理」FAQ にヒットして
     * 通常返金の「5〜7 営業日」を断定回答する問題への対処。元の単一 doc が複数概念を内包していたので、
     * cosine similarity が「破損品 + 返金」query にも引っかかってしまった。doc を「通常返品の返金日数」
     * 「破損品の返金 (別ルート)」と分けることで、LLM が「該当 doc が直接答えてるか否か」を判別しやすくする。
     */
    private fun seedFaqDocuments(): List<Document> = listOf(
        // 返品関連
        "通常返品の受付期間: 商品お届け後 30 日以内であれば、理由を問わず返品を受け付けます。",
        "通常返品の商品状態: 返品は未使用かつ元の梱包の状態でお戻しいただく必要があります。",
        // 返金関連
        "通常返品の返金日数: 通常の返品では、当社で返品商品を受領後 5〜7 営業日以内に返金処理を行います。",
        "返金の決済方法: 返金は購入時の決済方法（クレジットカード等）に対して行われます。",
        // 配送関連
        "通常配送の日数: 通常配送は 3〜5 営業日でお届けします。",
        "お急ぎ便の日数: お急ぎ便は追加料金で 1〜2 営業日でお届けします。",
        "当日カットオフ: 15 時以降のご注文は翌営業日の発送となります。",
        "配送状況の確認方法: 配送状況はマイページの注文詳細、または発送完了メールに記載のトラッキングリンクから確認できます。",
        // 注文操作
        "注文のキャンセル可否: 出荷準備に入る前であれば、手数料なしでキャンセル可能です。",
        "出荷後のキャンセル: 出荷後の注文をキャンセルしたい場合は、返品手続きとしてご対応ください。",
        // 破損品
        "破損品の連絡期限: 商品が破損して到着した場合は、お受け取りから 7 日以内に写真を添えてサポートまでご連絡ください。",
        "破損品の対応内容: 破損品は無償交換または全額返金で対応します。破損品の返金日数は個別にご案内します（通常返品の返金日数とは別ルート）。",
    ).map { Document.builder().text(it).build() }

    private companion object {
        private val log = LoggerFactory.getLogger(VectorStoreConfig::class.java)

        // text-embedding-3-small が返すベクトルの次元数
        private const val EMBEDDING_DIMENSIONS = 1536
    }
}
