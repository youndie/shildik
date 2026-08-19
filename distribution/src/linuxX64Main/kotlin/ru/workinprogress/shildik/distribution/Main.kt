package ru.workinprogress.shildik.distribution

import ru.workinprogress.shildik.auth.google.GoogleAuthMethod
import ru.workinprogress.shildik.auth.magic.MagicLinkAuthMethod
import ru.workinprogress.shildik.auth.password.PasswordAuthMethod
import ru.workinprogress.shildik.server.boot.optional
import ru.workinprogress.shildik.server.boot.runShildik
import ru.workinprogress.shildik.storage.sqlx4k.sqlx4kStorageModule

/**
 * A reference distribution: everything this repository carries, wired from the environment.
 *
 * It exists so the provider can be **run**, not only read — `./gradlew :distribution:image` builds
 * a container from it. Anything real is a `main()` of your own: which sign-in methods a build
 * carries is a decision about an installation, and making it here would hand you ours.
 *
 * **A method appears only when its configuration does.** No Google keys, no Google; no shared
 * secret, no magic link. That is the whole feature-flag story, and it is a property of the
 * process rather than of a settings file: what is absent cannot be switched on by mistake.
 *
 * Passwords are the exception worth noticing. They are here because a reference build should
 * demonstrate them; in the distribution that serves customers they are absent from the
 * **dependencies**, so "the product has no passwords" is a fact of the build rather than a
 * promise held up by configuration.
 */
fun main() =
    runShildik(
        storage = { config ->
            sqlx4kStorageModule(
                config.jdbcUrl,
                config.dbUser,
                config.dbPassword,
                // `migrate` reads a directory from the filesystem, so the schema travels as a
                // layer in the image rather than as a resource. No path, no migrations: applying
                // a schema to a database nobody asked about is worse than not starting.
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
