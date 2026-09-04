package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.runBlocking

/**
 * Migrations.
 *
 * **One file instead of twelve.** `migrations/1_schema.sql` is the whole schema at once rather
 * than a port of the previous migration history: there is nobody left to replay it against, and
 * both live databases are long past it. That the collapsed file yields the same schema as the
 * twelve steps did is checked by `SchemaParityTest`.
 *
 * **Read from the filesystem, shipped inside the artifact.** `migrate` takes a directory path:
 * sqlx4k reads files, not resources, and on Kotlin/Native there is no classpath to read from
 * anyway. The files nevertheless live in `commonMain/resources`, so the published jar carries the
 * schema — a consumer of the artifact would otherwise have no way to obtain it. Getting them out
 * is the build's job: resolve `storage-sqlx4k-jvm` as an artifact and unpack its `migrations`
 * directory next to the binary — a `Sync` task over `zipTree` does it in four lines.
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
            applyMigrations(db, path)
        } finally {
            // Commit rather than rollback: the lock has to be released either way, and this
            // transaction has nothing to undo — one lock and not a single write.
            lock.commit().getOrThrow()
        }
    }
}

/**
 * An arbitrary but **constant** number: only those who name the same one share the lock. Change
 * it and two rollouts stop seeing each other.
 */
private const val LOCK_KEY = 8_314_170_001L
