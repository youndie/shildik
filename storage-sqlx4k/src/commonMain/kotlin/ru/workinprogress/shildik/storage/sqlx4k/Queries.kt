package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * A query either joins an open transaction or goes past it — and that is decided **here**,
 * rather than in every repository.
 *
 * The rule is the one the previous adapter had: `withTransaction { }` in the domain means every
 * query inside lands in one transaction, and no repository method takes it as a parameter. With
 * sqlx4k the current transaction sits in the `CoroutineContext`, so asking for it before
 * executing is enough.
 */
internal suspend fun Driver.query(statement: Statement): ResultSet {
    // The transaction is read **here**, in the caller's context: the query itself leaves for
    // another coroutine, where that context no longer exists.
    val tx = TransactionContext.currentOrNull()
    return bounded { (tx?.fetchAll(statement) ?: fetchAll(statement)).getOrThrow() }
}

internal suspend fun Driver.exec(statement: Statement): Long {
    val tx = TransactionContext.currentOrNull()
    return bounded { (tx?.execute(statement) ?: execute(statement)).getOrThrow() }
}

/**
 * A query must **end** — with an answer if possible, with an error otherwise.
 *
 * How to bound it depends on the platform, and that is not a whim. On the JVM the call behaves:
 * it suspends, so an ordinary `withTimeout` is enough. On Kotlin/Native the same call goes into
 * Rust and **never returns control** — there is nothing to cancel and `withTimeout` is useless
 * (measured). There the wait has to be bounded rather than the call: the query leaves for a
 * coroutine of its own and we wait on it no longer than the deadline.
 *
 * Hiding that difference behind one implementation is not worth it: a shared one would either be
 * powerless on native or drag a separate thread pool onto the JVM where none is needed — and
 * would one day run that pool dry. It did: the tests of two storages shared a single pool.
 */
internal expect suspend fun <T> bounded(block: suspend () -> T): T

/** Longer than any query of ours and noticeably shorter than a client's patience. */
internal val QUERY_TIMEOUT = 10.seconds

/** Our own failure, not a refusal by protocol: the OIDC surface answers 500 and reports it. */
class StorageUnavailable(
    message: String,
) : RuntimeException(message)

// Typed row getters live in `impl.extensions` rather than on `Column` itself, which only has
// string ones. That is the first place the README and the artifact disagree, and not the last.

/** A value may be absent: `NULL` in the database is not an empty string. */
internal fun ResultSet.Row.text(name: String): String? = get(name).asStringOrNull()

internal fun ResultSet.Row.requiredText(name: String): String = get(name).asString()

internal fun ResultSet.Row.number(name: String): Long = get(name).asLong()

internal fun ResultSet.Row.numberOrNull(name: String): Long? = get(name).asLongOrNull()

internal fun ResultSet.Row.flag(name: String): Boolean = get(name).asBoolean()

/**
 * Parameters are **named only**.
 *
 * sqlx4k can do positional ones too, but in a query with eight fields getting the order wrong is
 * a matter of time — and the mistake is silent: the types match and the values land elsewhere.
 */
internal fun sql(text: String): Statement = Statement.create(text)
