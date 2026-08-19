package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
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
fun postgres(
    jdbcUrl: String,
    user: String,
    password: String,
    maxConnections: Int = DEFAULT_POOL,
): PostgreSQL =
    PostgreSQL(
        url = jdbcUrl.removePrefix("jdbc:"),
        username = user,
        password = password,
        options =
            ConnectionPool.Options
                .builder()
                .maxConnections(maxConnections)
                // **Waiting forever for a connection is not an option.** Without a timeout,
                // Postgres going away left the query hanging with no answer: no error for the
                // client, no report in telemetry, no reason for a probe. Five seconds is well
                // past a network hiccup and well short of a client's patience.
                .acquireTimeout(ACQUIRE_TIMEOUT)
                .build(),
    )

/**
 * The same as the previous adapter had: a pilot is compared against current behaviour rather
 * than against behaviour configured anew.
 */
private const val DEFAULT_POOL = 10

private val ACQUIRE_TIMEOUT = 5.seconds
