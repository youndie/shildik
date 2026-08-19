package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Migrations.
 *
 * **One file instead of twelve.** `migrations/1_schema.sql` is the whole schema at once rather
 * than a port of the previous migration history: there is nobody left to replay it against, and
 * both live databases are long past it. That the collapsed file yields the same schema as the
 * twelve steps did is checked by `SchemaParityTest`.
 *
 * **Files, not resources.** The directory is read from the filesystem rather than from a jar. For
 * the image that means a layer of its own for the migrations and a path in the configuration.
 *
 * **About the baseline.** Both live databases were already migrated by the previous tool: the
 * schema is in place and its history in its own table. An empty `_sqlx4k_migrations` would apply
 * the schema on top of a non-empty database — that is, break start-up. So the move is not "turn
 * it on and go": either the database is created afresh, or the sqlx4k table is marked as applied
 * once. There is deliberately **no** automatic detection here: guessing the state of somebody
 * else's migration history is more dangerous than stopping to look.
 */
fun migrate(
    db: Driver,
    path: String,
) {
    runBlocking {
        // A lock is mandatory, and we have to take it ourselves: the sqlx4k migrator of 1.13.0
        // has none — it creates its bookkeeping table, reads what has been applied and rolls the
        // files, doing nothing to keep two processes apart. With a single pod that goes unnoticed;
        // a zero-downtime rollout starts the second pod before stopping the first by definition,
        // and both go migrating at once. The previous tool had a lock, and losing one silently is
        // the worst way to save effort.
        //
        // The lock is held by a **transaction of its own**: `pg_advisory_xact_lock` lives until
        // its transaction ends, while the migrations themselves go over other connections of the
        // pool. Whoever arrives late waits on this very line, enters after, and finds nothing to
        // apply.
        val lock = db.begin().getOrThrow()
        try {
            lock.execute(Statement.create("select pg_advisory_xact_lock($LOCK_KEY)")).getOrThrow()
            db.migrate(supplier = { read(path) }).getOrThrow()
        } finally {
            // Commit rather than rollback: the lock has to be released either way, and this
            // transaction has nothing to undo — one lock and not a single write.
            lock.commit().getOrThrow()
        }
    }
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

/**
 * An arbitrary but **constant** number: only those who name the same one share the lock. Change
 * it and two rollouts stop seeing each other.
 */
private const val LOCK_KEY = 8_314_170_001L
