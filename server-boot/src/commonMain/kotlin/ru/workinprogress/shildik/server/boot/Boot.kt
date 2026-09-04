package ru.workinprogress.shildik.server.boot

import io.ktor.server.application.Application
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import ru.workinprogress.shildik.core.config.ShildikConfig
import ru.workinprogress.shildik.core.feature.auth.AuthMethod
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.server.ErrorReporter
import ru.workinprogress.shildik.server.shildikServer

/**
 * Starting a distribution.
 *
 * Distributions differ in **one** thing only: which sign-in methods they carry. Everything else is
 * shared and lives here — otherwise two entry points start drifting apart in small ways, in how
 * they read the environment, in start-up order, and they drift silently.
 *
 * The whole configuration is read from the environment, and secrets deliberately have no
 * defaults: a default for a secret is a way to reach production with a default secret.
 *
 * @param storage the storage comes **from outside** rather than being chosen here. It used to be
 *   wired in through an ORM, and that made the shared start-up code JVM-only: JDBC is a JVM
 *   interface, not a protocol. There is deliberately no default — a default would bring the same
 *   dependency back, and the first native build would walk into it again.
 * @param observability whatever a distribution wants attached to the public contour: metrics, a
 *   tracer, nothing. It is a parameter rather than a hook inside, because a provider that fails
 *   to start over unreachable telemetry takes sign-in down for a graph.
 * @param reporter where unexpected failures of the OIDC surface go. The default is the log rather
 *   than silence: "nothing arrives in monitoring" is otherwise indistinguishable from "nothing is
 *   breaking".
 * @param authMethods assembled **inside** the container: a password method needs repositories, and
 *   they live there.
 */
fun runShildik(
    storage: (ShildikConfig) -> Module,
    observability: Application.() -> Unit = {},
    reporter: ErrorReporter = ErrorReporter.Logging,
    authMethods: Scope.() -> List<AuthMethod>,
) {
    val config = loadConfig()
    shildikServer(
        config = config,
        storage =
            module {
                includes(storage(config))
                // Sign-in methods are wired by a line in the distribution's build, not by
                // reflection and not by a jar in a directory.
                single { AuthMethodRegistry(authMethods()) }
            },
        observability = observability,
        reporter = reporter,
    ).start(wait = true)
}

/** The build version, for whatever a distribution attaches as observability. */
val release: String get() = optional("SHILDIK_RELEASE") ?: "dev"

private fun loadConfig(): ShildikConfig {
    // A file has no account. `SHILDIK_DB_PATH` is what tells the two storages apart here, and it
    // is the reason the database user and password stop being mandatory: demanding them from an
    // installation that runs on SQLite would mean inventing two values for nobody to use, and a
    // secret that exists only to satisfy a check is a secret somebody sets to `x`.
    val databasePath = optional("SHILDIK_DB_PATH")

    return ShildikConfig(
        issuer = required("SHILDIK_ISSUER"),
        publicPort = optional("SHILDIK_PORT")?.toInt() ?: 8080,
        managementPort = optional("SHILDIK_MANAGEMENT_PORT")?.toInt() ?: 9000,
        // Several keys separated by commas: the first is current, the rest are for a rotation.
        masterKeys = required("SHILDIK_MASTER_KEYS").split(",").map(String::trim).filter(String::isNotBlank),
        jdbcUrl = optional("SHILDIK_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/shildik",
        dbUser = if (databasePath == null) required("SHILDIK_DB_USER") else optional("SHILDIK_DB_USER").orEmpty(),
        dbPassword =
            if (databasePath == null) required("SHILDIK_DB_PASSWORD") else optional("SHILDIK_DB_PASSWORD").orEmpty(),
        bootstrapToken = optional("SHILDIK_BOOTSTRAP_TOKEN"),
        databasePath = databasePath,
    )
}

private fun required(name: String): String =
    optional(name) ?: error(
        "Environment variable $name is required. Secrets deliberately have no defaults: " +
            "a default for a secret is a way to reach production with a default secret.",
    )

/**
 * Reading an environment variable — the **only** platform-specific place in start-up.
 *
 * `System.getenv` on the JVM, posix `getenv` on native. A blank string counts as absent: "set to
 * empty" and "not set" are one thing here, and telling them apart would invent a third state for
 * nothing.
 */
expect fun optional(name: String): String?
