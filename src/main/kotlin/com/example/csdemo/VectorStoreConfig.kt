package com.example.csdemo

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SimpleVectorStore
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Step 4-1：FAQ grounding 用の in-memory な [VectorStore] を立てる。
 *
 * Bean 構築時に seed FAQ を embed して投入するため、起動時点で
 * OpenAI Embedding API を叩く。`OPENAI_API_KEY` が有効である必要がある。
 *
 * Step 4-2 で `koog-spring-ai-starter-vector-store` の bridge を入れ、
 * この VectorStore を Koog 側 LongTermMemory feature の backend として使う。
 */
@Configuration
class VectorStoreConfig {

    @Bean
    fun vectorStore(embeddingModel: EmbeddingModel): VectorStore =
        SimpleVectorStore.builder(embeddingModel).build().apply {
            add(seedFaqDocuments())
        }

    private fun seedFaqDocuments(): List<Document> = listOf(
        "返品ポリシー: 商品お届け後 30 日以内であれば理由を問わず返品を受け付けます。未使用かつ元の梱包の状態でお戻しください。",
        "返金処理: 返品商品を当社で受領後、5〜7 営業日以内に返金を処理します。返金はご購入時の決済方法に対して行われます。",
        "配送日数: 通常配送は 3〜5 営業日でお届けします。お急ぎ便（追加料金）は 1〜2 営業日です。15 時以降のご注文は翌営業日の発送となります。",
        "注文の追跡: マイページの注文詳細から、または発送完了メールに記載のトラッキングリンクから、配送状況をご確認いただけます。",
        "注文のキャンセル: 出荷準備に入る前であれば手数料なしでキャンセル可能です。出荷後の場合は返品手続きとしてご対応ください。",
        "破損品の対応: 商品が破損して到着した場合は、お受け取りから 7 日以内に写真を添えてサポートまでご連絡ください。無償交換または全額返金で対応します。",
    ).map { Document.builder().text(it).build() }
}
