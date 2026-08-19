package ru.workinprogress.shildik.storage.sqlx4k

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On native the **wait** is bounded rather than the call.
 *
 * The call goes into Rust and never gives control back: a `withTimeout` around it cancels
 * nothing, and the pool's `acquireTimeout` bounds only acquiring a connection. So the query leaves
 * for a coroutine of its own and we stop waiting on a deadline. A hung call lives out its life and
 * takes a thread with it — that is the price, and it is smaller than a client with no answer.
 */
internal actual suspend fun <T> bounded(block: suspend () -> T): T {
    val call = calls.async { block() }
    return withTimeoutOrNull(QUERY_TIMEOUT) { call.await() }
        ?: throw StorageUnavailable("the database did not answer within $QUERY_TIMEOUT")
}

/**
 * A pool of its own rather than `Dispatchers.Default`: that one is bounded by the number of
 * cores — we have two — and hung calls would take it whole, stopping even what does not need the
 * database. `Dispatchers.IO` does not exist on Kotlin/Native.
 *
 * The size follows the connection count: the database pool would not let more queries through
 * anyway.
 */
private val calls = CoroutineScope(newFixedThreadPoolContext(CALL_THREADS, "sqlx4k") + SupervisorJob())

private const val CALL_THREADS = 10
