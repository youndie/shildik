package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The same files, applied without a lock of our own.
 *
 * For SQLite, where there is nobody to keep out. The lock above answers a rollout that starts a
 * second pod before stopping the first; a SQLite installation cannot have a second pod at all,
 * because two of them would be sharing one file — a broken database rather than a race between
 * migrations. Advisory locks are Postgres's, and imitating one here would mean inventing a
 * mechanism to protect against a deployment that is already impossible.
 */
fun migrateUnlocked(
    db: Driver,
    path: String,
) {
    runBlocking { applyMigrations(db, path) }
}

/**
 * Applying the files, as a suspending call — what both storages' entry points come down to.
 *
 * Public because the Postgres module holds a lock around exactly this and cannot call
 * [migrateUnlocked]: that one opens a `runBlocking` of its own, and nesting one inside the
 * transaction that holds the lock would block the thread the lock is waiting on.
 */
suspend fun applyMigrations(
    db: Driver,
    path: String,
) {
    db.migrate(supplier = { read(path) }).getOrThrow()
}

/**
 * Reading the migration files is **ours** rather than what sqlx4k has built in.
 *
 * `db.migrate(path)` reads a file like this: `source.readAtMostTo(buffer, Int.MAX_VALUE.toLong())`.
 * On native that turns into asking for a two-gigabyte buffer — per file, regardless of
 * its real size. Our file is seven kilobytes, and a pod with a 256Mi limit died of OOM in the
 * same second it started.
 *
 * It was nasty to catch: the spike lives for a second, an ordinary `kubectl top` never sees it,
 * and the first log line is written **after** the migrations — so the failure looked like "starts
 * and silently dies". A measurement taken after start-up showed an honest 30 MB and misled.
 *
 * Here the file is read as a stream: `readString` collects the contents as they arrive and
 * reserves nothing up front. The checksum stays the same (`String.hashCode` of the same text), so
 * a baseline stamped before this change remains valid.
 */
private fun read(path: String): List<MigrationFile> {
    val directory = Path(path)
    require(SystemFileSystem.metadataOrNull(directory)?.isDirectory == true) {
        "migrations directory not found: $path"
    }

    return SystemFileSystem
        .list(directory)
        .filter { it.name.endsWith(".sql", ignoreCase = true) }
        .map { file ->
            MigrationFile(file.name, SystemFileSystem.source(file).buffered().use { it.readString() })
        }
}
