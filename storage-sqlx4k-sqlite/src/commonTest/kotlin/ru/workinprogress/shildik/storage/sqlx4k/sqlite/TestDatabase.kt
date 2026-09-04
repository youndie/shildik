package ru.workinprogress.shildik.storage.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.Driver
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import ru.workinprogress.shildik.storage.sqlx4k.migrateUnlocked

/**
 * A database per test, in a file.
 *
 * **A file rather than `:memory:`,** although sqlx4k offers one. An in-memory database belongs to
 * the connection that opened it, and a pool hands out more than one: the migrations would land in
 * the first connection's database and the queries would go to a second, empty one. That failure
 * reads as "no such table" and sends the search to the migrations, which are fine.
 *
 * The path and the migrations directory both come from the build (`SHILDIK_TEST_TMP`,
 * `SHILDIK_TEST_MIGRATIONS`), because a test's working directory is not a fact worth relying on
 * and differs between the JVM and native runners. The migrations read are **the ones the artifact
 * ships** — a copy made for the tests would be a copy that can drift.
 */
internal class TestDatabase private constructor(
    val db: Driver,
) {
    fun close() {
        runBlocking { db.close() }
    }

    companion object {
        fun open(name: String): TestDatabase {
            val directory = requireNotNull(env("SHILDIK_TEST_TMP")) { "SHILDIK_TEST_TMP is not set by the build" }
            val migrations =
                requireNotNull(env("SHILDIK_TEST_MIGRATIONS")) { "SHILDIK_TEST_MIGRATIONS is not set by the build" }

            val path = "$directory/$name-${counter++}.db"
            // **Deleted first, not merely named uniquely.** The names repeat from run to run and
            // the directory survives, so a second run would open the first run's database — with
            // its rows still in it. That failure arrives as a unique-constraint violation on
            // whatever the test inserts first, which reads as a broken test rather than a stale
            // file. The journal files travel with the database and have to go with it.
            listOf(path, "$path-wal", "$path-shm").forEach {
                SystemFileSystem.delete(Path(it), mustExist = false)
            }

            val db = sqlite(path)
            migrateUnlocked(db, migrations)
            return TestDatabase(db)
        }

        private var counter = 0
    }
}

/**
 * The environment, read the same way on both platforms — `System.getenv` is the JVM's and
 * `getenv` is POSIX's, and neither is common code.
 */
internal expect fun env(name: String): String?

/**
 * Whether this platform's driver enforces the schema's foreign keys.
 *
 * SQLite leaves them off per connection and each driver decides for itself: sqlx turns them on
 * when it opens a connection, so the native build — the one that ships — refuses a row pointing at
 * a tenant that is not there. The JVM driver does not, and there is no way to ask it through
 * sqlx4k: the connection URL takes only SQLite's four filename parameters, and a `PRAGMA` reaches
 * one connection of a pool nobody here holds.
 *
 * Written down as a fact of the platform rather than hidden behind a test that passes on both:
 * production is native, and the difference belongs where a reader will meet it.
 */
internal expect val foreignKeysEnforced: Boolean
