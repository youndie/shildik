package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Assembling the storage on sqlx4k — the same set of ports the previous adapter offered.
 *
 * The signatures match on purpose: swapping the adapter at the assembly point has to be a
 * one-line change, or the two storages cannot be compared with one and the same test suite.
 * (research-native §7.1).
 */
fun sqlx4kStorageModule(
    jdbcUrl: String,
    user: String,
    password: String,
    migrationsPath: String? = null,
): Module =
    module {
        includes(sqlx4kPorts())
        single<Driver> {
            postgres(jdbcUrl, user, password).also { db ->
                // Migrations are the storage's business, as before: a service must not start on
                // a schema it does not know.
                migrationsPath?.let { path -> migrate(db, path) }
            }
            // The pool closes with the container — otherwise connections outlive the server. The
            // previous adapter had no such worry: it took a connection per transaction and gave
            // it back, so there was nothing to close. Here the pool keeps them open, and an
            // unclosed server carries a dozen away with it. In production there is one server and
            // it lives as long as the pod, so this only showed up in tests: forty servers in one
            // JVM exhausted Postgres `max_connections`, and the **whole** suite fell over on
            // `too many clients`.
        } onClose { driver -> driver?.let { db -> runBlocking { db.close() } } }
    }
