package com.example.csdemo

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Postgres backed [CancelledOrderStore]。
 *
 * schema 初期化は PgVectorStore と同じく Spring lifecycle ([InitializingBean.afterPropertiesSet])
 * で行うことで、Bean が利用される前に table を確実に作る。
 *
 * 冪等性は `INSERT ... ON CONFLICT (order_id) DO NOTHING RETURNING cancelled_at` で実現:
 * - 競合なし -> RETURNING が row を返し [CancelResult.Accepted]
 * - 競合あり -> RETURNING が空なので SELECT で既存時刻を取り [CancelResult.AlreadyCancelled]
 *
 * これにより同時 2 リクエストでも DB の UNIQUE 制約により Accepted を取れるのは 1 つだけになる。
 */
@Service
class JdbcCancelledOrderStore(
    private val jdbcTemplate: JdbcTemplate,
) : CancelledOrderStore, InitializingBean {

    override fun afterPropertiesSet() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS cancelled_orders (
                order_id TEXT PRIMARY KEY,
                cancelled_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        )
        log.info("cancelled_orders schema is ready")
    }

    override fun cancel(orderId: String): CancelResult {
        val inserted = jdbcTemplate.query(
            """
            INSERT INTO cancelled_orders (order_id)
            VALUES (?)
            ON CONFLICT (order_id) DO NOTHING
            RETURNING cancelled_at
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp("cancelled_at").toInstant() },
            orderId,
        ).firstOrNull()

        if (inserted != null) {
            return CancelResult.Accepted(inserted)
        }

        val existing = jdbcTemplate.queryForObject(
            "SELECT cancelled_at FROM cancelled_orders WHERE order_id = ?",
            { rs, _ -> rs.getTimestamp("cancelled_at").toInstant() },
            orderId,
        )!!
        return CancelResult.AlreadyCancelled(existing)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(JdbcCancelledOrderStore::class.java)
    }
}
