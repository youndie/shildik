package ru.workinprogress.shildik.distribution.sqlite

import ru.workinprogress.shildik.auth.google.GoogleAuthMethod
import ru.workinprogress.shildik.auth.magic.MagicLinkAuthMethod
import ru.workinprogress.shildik.auth.password.PasswordAuthMethod
import ru.workinprogress.shildik.server.boot.optional
import ru.workinprogress.shildik.server.boot.runShildik
import ru.workinprogress.shildik.storage.sqlx4k.sqlite.sqlx4kSqliteStorageModule

/**
 * The reference distribution, storing everything in one file.
 *
 * The same provider as [ru.workinprogress.shildik.distribution] — the same sign-in methods, the
 * same two ports, the same rule that a method appears only when its configuration does. One line
 * differs, and it is the line naming the storage.
 *
 * **Who this is for.** An installation that runs a single instance and would otherwise operate a
 * PostgreSQL for a database whose entire traffic is people signing in. What it costs is stated
 * plainly rather than in a footnote: a SQLite file cannot be shared by two pods, so this build
 * runs **one** instance and takes its downtime on every deploy. An installation that needs a
 * rolling restart wants the Postgres image next door, and the swap is one line here.
 *
 * `SHILDIK_DB_PATH` says where the file lives, and there is no default: the image's own filesystem
 * is not a volume, and a database that disappears with the container is worse than a refusal to
 * start.
 */
fun main() =
    runShildik(
        storage = { config ->
            sqlx4kSqliteStorageModule(
                config,
                // The schema travels as a layer in the image, as in the Postgres distribution —
                // `migrate` reads a directory, not resources. The Dockerfile points this at
                // `/app/migrations`, and what is copied there is *this* module's set.
                migrationsPath = optional("SHILDIK_MIGRATIONS"),
            )
        },
    ) {
        buildList {
            val googleId = optional("GOOGLE_CLIENT_ID")
            val googleSecret = optional("GOOGLE_CLIENT_SECRET")
            if (googleId != null && googleSecret != null) add(GoogleAuthMethod(googleId, googleSecret))

            optional("MAGIC_HANDOFF_SECRET")?.let { add(MagicLinkAuthMethod(it)) }

            add(PasswordAuthMethod(get(), get(), get(), get()))
        }
    }
