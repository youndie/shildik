package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import ru.workinprogress.shildik.core.port.TransactionManager

/**
 * The transaction travels in the `CoroutineContext` rather than in domain signatures.
 *
 * With sqlx4k this is built in: `TransactionContext.withCurrent` joins an already open
 * transaction or starts a new one. So the rule "a nested `withTransaction` does not open a second
 * transaction" is enforced by the library here, where the previous adapter had to hold it by hand.
 *
 * There is no bridge to the blocking world either: the driver is asynchronous, `Dispatchers.IO`
 * is not needed.
 */
class Sqlx4kTransactionManager(
    private val db: Driver,
) : TransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = TransactionContext.withCurrent(db) { block() }
}
