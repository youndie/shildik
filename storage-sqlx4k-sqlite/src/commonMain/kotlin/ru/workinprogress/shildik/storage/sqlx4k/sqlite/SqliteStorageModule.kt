package ru.workinprogress.shildik.storage.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Driver
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import ru.workinprogress.shildik.core.config.ShildikConfig
import ru.workinprogress.shildik.storage.sqlx4k.migrateUnlocked
import ru.workinprogress.shildik.storage.sqlx4k.sqlx4kPorts

/**
 * Assembling the storage on SQLite — the same ports the Postgres adapter offers.
 *
 * The repositories are not re-declared here: `sqlx4kPorts()` is the one list, and this module
 * supplies the driver it is missing. Swapping the storage in a distribution's `main()` stays what
 * it was meant to be — one line naming a different module:
 *
 * ```kotlin
 * runShildik(storage = { config -> sqlx4kSqliteStorageModule(config.databasePath) }) { … }
 * ```
 *
 * **No user and no password.** They are not omitted for brevity: a file has an owner and a mode,
 * not an account. A signature carrying two empty strings would suggest there is something to
 * configure.
 */
fun sqlx4kSqliteStorageModule(
    databasePath: String,
    migrationsPath: String? = null,
): Module =
    module {
        includes(sqlx4kPorts())
        single<Driver> {
            sqlite(databasePath).also { db ->
                // Migrations are the storage's business, as in the Postgres adapter: a service
                // must not start on a schema it does not know. The lock that one takes is absent
                // here — `migrateUnlocked` says why.
                migrationsPath?.let { path -> migrateUnlocked(db, path) }
            }
        } onClose { driver -> driver?.let { db -> runBlocking { db.close() } } }
    }

/**
 * The same module, taking the path from the configuration — what a distribution's `main()` uses.
 *
 * ```kotlin
 * runShildik(storage = { config -> sqlx4kSqliteStorageModule(config, optional("SHILDIK_MIGRATIONS")) }) { … }
 * ```
 *
 * It refuses rather than defaults. A build that depends on this module stores everything it has in
 * that file, and picking a path on its behalf would put a production database somewhere nobody
 * chose — a directory that may not be a volume, and whose contents disappear with the container.
 */
fun sqlx4kSqliteStorageModule(
    config: ShildikConfig,
    migrationsPath: String? = null,
): Module =
    sqlx4kSqliteStorageModule(
        databasePath =
            requireNotNull(config.databasePath) {
                "SHILDIK_DB_PATH is required: this build stores its data in SQLite and has nowhere to put it"
            },
        migrationsPath = migrationsPath,
    )
