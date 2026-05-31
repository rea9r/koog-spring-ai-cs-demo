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
        "Our standard return policy allows returns within 30 days of delivery for any reason. Items must be unused and in original packaging.",
        "Refunds are processed within 5-7 business days after we receive your returned item. The refund is issued to your original payment method.",
        "Standard shipping takes 3-5 business days. Express shipping (extra fee) takes 1-2 business days. Orders placed after 3pm ship the next business day.",
        "You can track your order from the order detail page in your account, or via the tracking link in your shipping confirmation email.",
        "Orders can be cancelled free of charge until they enter the shipping queue. Once shipped, treat the cancellation as a return request.",
        "If your item arrived damaged, contact support with a photo within 7 days. We will send a replacement at no cost or offer a full refund.",
    ).map { Document.builder().text(it).build() }
}
