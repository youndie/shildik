package ru.workinprogress.shildik.core.port

/**
 * The transaction port. The domain writes `withTransaction { }` and **receives no handle in the
 * signature** — the transaction carrier travels in the `CoroutineContext` on the adapter's side
 * (research §R4).
 *
 * It lives in `:core` deliberately: elsewhere the same interface sits in one module with its Mongo
 * implementation, packing the port together with the adapter. Not here.
 */
interface TransactionManager {
    suspend fun <T> withTransaction(block: suspend () -> T): T
}

/**
 * A transaction-free implementation — for builds whose storage does not require them (domain tests,
 * for instance). It exists so that the port could be introduced at once, without waiting for an
 * adapter (research §Risk 3).
 */
object NoopTransactionManager : TransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
}
