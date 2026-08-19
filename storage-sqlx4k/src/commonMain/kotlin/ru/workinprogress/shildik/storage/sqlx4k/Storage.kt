package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.time.Duration.Companion.seconds

/**
 * The connection to the database.
 *
 * **The address is converted from the JDBC form.** A service's configuration holds
 * `jdbc:postgresql://...` — that is what the chart and the environment variables set — and
 * changing it for the sake of a second adapter would mean touching deployment before the adapter
 * had earned its place. sqlx4k expects `postgresql://...`, so the prefix comes off here, in one
 * place.
 */
expect fun postgres(
    jdbcUrl: String,
    user: String,
    password: String,
    maxConnections: Int = DEFAULT_POOL,
): Driver

/**
 * The pool's settings, shared by both platforms.
 *
 * They live here and the constructor call does not, because `PostgreSQL` is declared per platform
 * in sqlx4k and is not part of its common API: common code that names the type compiles for every
 * target and then fails to compile as metadata — which is exactly what happened when this module
 * was first published.
 */
internal fun poolOptions(maxConnections: Int): ConnectionPool.Options =
    ConnectionPool.Options
        .builder()
        .maxConnections(maxConnections)
        // **Waiting forever for a connection is not an option.** Without a timeout, Postgres going
        // away left the query hanging with no answer: no error for the client, no report in
        // telemetry, no reason for a probe. Five seconds is well past a network hiccup and well
        // short of a client's patience.
        .acquireTimeout(ACQUIRE_TIMEOUT)
        .build()

/** The JDBC form is what a service's configuration holds; sqlx4k wants it without the prefix. */
internal fun String.toSqlx4kUrl(): String = removePrefix("jdbc:")

/**
 * The same as the previous adapter had: a pilot is compared against current behaviour rather
 * than against behaviour configured anew.
 */
internal const val DEFAULT_POOL = 10

private val ACQUIRE_TIMEOUT = 5.seconds
