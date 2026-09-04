package ru.workinprogress.shildik.storage.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite as sqlx4kSqlite

/**
 * Opening the database.
 *
 * The address is a **path**, not a JDBC URL. The Postgres adapter takes `jdbc:postgresql://…`
 * because that is what its charts and environment already hold; nothing holds a JDBC URL for a
 * file, and inventing `jdbc:sqlite:` here would be a prefix that exists only to be stripped again.
 *
 * `sqlite://` plus the path is what sqlx4k wants, and the two shapes fall out of it by themselves:
 * a relative path stays relative to the process's directory, an absolute one keeps its leading
 * slash and reads as `sqlite:///var/lib/…`.
 */
fun sqlite(
    databasePath: String,
    maxConnections: Int = SQLITE_POOL,
): Driver {
    ensureDatabaseFile(databasePath)
    // Aliased on import: this function carries the same name on purpose — the Postgres adapter
    // offers `postgres(…)` and a reader looking for its counterpart should find `sqlite(…)`.
    return sqlx4kSqlite(url = sqliteUrl(databasePath), options = sqlitePoolOptions(maxConnections))
}

/**
 * **Two connections, not ten.**
 *
 * SQLite has one writer, whatever the pool says, and every extra connection sqlx4k opens brings an
 * OS thread, a page cache and a prepared-statement cache of its own. The Postgres adapter's ten
 * are right for a server that answers them in parallel and wrong for a file.
 */
const val SQLITE_POOL: Int = 2

/**
 * The URL sqlx4k is given: the path, and nothing else.
 *
 * **There is no pragma to add here, and that was worth finding out.** sqlx4k accepts exactly the
 * four parameters SQLite defines for URI filenames — `mode`, `cache`, `immutable`, `vfs` — and
 * rejects anything else. `?foreign_keys=on` was tried first: the JVM driver took it (xerial passes
 * unknown keys to SQLite) and the native one refused the connection URL outright, panicking in
 * Rust before the first log line. One platform enforcing referential integrity and the other
 * failing to start is the worst of both, so neither does now — see the schema for what that means.
 *
 * The JVM driver also keeps a second parameter inside the **file name**: with
 * `?mode=rwc&foreign_keys=on` the database landed on disk as `local.db?mode=rwc`. `mode=rwc` is
 * gone too, and the file is created by [ensureDatabaseFile] instead — one behaviour on both
 * platforms rather than two drivers' worth of query-string parsing.
 */
internal fun sqliteUrl(databasePath: String): String = "sqlite://$databasePath"

/**
 * The pool's settings.
 *
 * Unlike the Postgres adapter, there is no `expect`/`actual` split here and none is needed:
 * sqlx4k declares SQLite's constructor in common code, so one function serves both the JVM and
 * the native target.
 */
internal fun sqlitePoolOptions(maxConnections: Int): ConnectionPool.Options =
    ConnectionPool.Options
        .builder()
        .maxConnections(maxConnections)
        // A connection is acquired, not awaited forever: with one writer a stuck transaction would
        // otherwise hold every caller with no error to report and nothing for a probe to see.
        .acquireTimeout(ACQUIRE_TIMEOUT)
        .build()

/**
 * The directory and the file, both created here if they are not there.
 *
 * **Not left to the driver, because the two platforms disagree.** The JVM driver creates a missing
 * database by itself; the native one does not — sqlx opens read-write and reports a database that
 * is not there as one it cannot open. A first start on an empty volume is exactly that case, so
 * the file is made here and both platforms take the same path afterwards.
 *
 * The directory above it is nobody's job either: a volume mounted at `/data` arrives empty, and
 * the failure without this reads as "unable to open database file", which sounds like permissions
 * and is not.
 */
internal fun ensureDatabaseFile(databasePath: String) {
    val path = Path(databasePath)
    path.parent?.let { parent ->
        if (SystemFileSystem.metadataOrNull(parent) == null) SystemFileSystem.createDirectories(parent)
    }
    if (SystemFileSystem.metadataOrNull(path) == null) SystemFileSystem.sink(path).close()
}

private val ACQUIRE_TIMEOUT = 5.seconds
