package io.github.youndie.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.youndie.shildik.core.port.StorageHealth
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cheapest query a database can answer: it checks the connection, not the schema.
 *
 * The error is swallowed on purpose — an unreachable database is `false` rather than an
 * exception: a readiness probe has to answer, not to throw.
 */
public class Sqlx4kStorageHealth(
    private val db: Driver,
) : StorageHealth {
    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "недоступная база — это `false`, а не исключение: проба готовности обязана отвечать",
    )
    override suspend fun check(): Boolean =
        // Отмена — это ушедший проверяющий, а не непригодное хранилище: отвечать на неё `false`
        // значит сообщать о неготовности тому, кто уже не слушает, и рисковать перезапуском.
        try {
            db.exec(sql("select 1"))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
}
