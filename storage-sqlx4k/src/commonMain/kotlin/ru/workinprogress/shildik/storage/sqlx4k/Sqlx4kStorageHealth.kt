package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import ru.workinprogress.shildik.core.port.StorageHealth

/**
 * The cheapest query a database can answer: it checks the connection, not the schema.
 *
 * The error is swallowed on purpose — an unreachable database is `false` rather than an
 * exception: a readiness probe has to answer, not to throw.
 */
class Sqlx4kStorageHealth(
    private val db: Driver,
) : StorageHealth {
    override suspend fun check(): Boolean = runCatching { db.exec(sql("select 1")) }.isSuccess
}
