package com.example.csdemo

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 注文キャンセルの永続記録。
 *
 * Step 5-A1 の継続で観察した「history compression 後に LLM が処理済み注文を再キャンセルする」現象に対し、
 * prompt engineering だけに頼らず tool 側で物理的に防御するための store。
 *
 * production では multi-pod 越しに state を共有する必要があるため、本 demo の実体は
 * [JdbcCancelledOrderStore]（Postgres backed）。test と interface 契約確認用に
 * [InMemoryCancelledOrderStore] を併設し、Bean 差し替えだけで infra を切り替えられる構造を保つ。
 */
interface CancelledOrderStore {
    fun cancel(orderId: String): CancelResult
}

sealed interface CancelResult {
    val cancelledAt: Instant

    /** 初回の cancel 呼び。store に新規記録された */
    data class Accepted(override val cancelledAt: Instant) : CancelResult

    /** 既に同じ orderId が記録済み。cancelledAt は初回時の値 */
    data class AlreadyCancelled(override val cancelledAt: Instant) : CancelResult
}

class InMemoryCancelledOrderStore : CancelledOrderStore {
    private val cancelledAt = ConcurrentHashMap<String, Instant>()

    override fun cancel(orderId: String): CancelResult {
        val now = Instant.now()
        val existing = cancelledAt.putIfAbsent(orderId, now)
        return if (existing == null) {
            CancelResult.Accepted(now)
        } else {
            CancelResult.AlreadyCancelled(existing)
        }
    }
}
