package com.example.csdemo

import ai.koog.agents.longtermmemory.storage.InMemoryRecordStorage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Step 5-A10 (A2): Koog の `LongTermMemory` feature 用の record storage を Spring Bean として提供する。
 *
 * Step 4-3 で `LongTermMemory.Feature` を一度入れて外した経緯がある（FAQ retrieval 用途には手書きの
 * `vectorStore.search()` の方が明示的で分かりやすかった）。A2 では用途を変えて「同一 session 内の
 * 会話事実 (user 発話) を semantic record として ingestion し、後続 turn で retrieval する」
 * conversation memory として使い直す。ChatMemory (単純な history) との棲み分けを観察する。
 *
 * 注意: 本 demo の [InMemoryRecordStorage] は実装上は substring matching ベース (vector embedding なし)。
 * 本気の semantic search が必要なら PgVector backed の SearchStorage 実装に差し替える。
 */
@Configuration
class LongTermMemoryConfig {

    @Bean
    fun longTermMemoryStorage(): InMemoryRecordStorage = InMemoryRecordStorage()
}
