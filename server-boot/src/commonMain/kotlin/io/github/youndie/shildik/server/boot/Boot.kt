package io.github.youndie.shildik.server.boot

import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.kore.version.BuildIdentity
import io.github.youndie.shildik.core.config.ShildikConfig
import io.github.youndie.shildik.core.feature.auth.AuthMethod
import io.github.youndie.shildik.core.feature.auth.AuthMethodRegistry
import io.github.youndie.shildik.server.ErrorReporter
import io.github.youndie.shildik.server.shildikServer
import io.ktor.server.application.Application
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

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
public fun runShildik(
    storage: (ShildikConfig) -> Module,
    observability: Application.() -> Unit = {},
    reporter: ErrorReporter = ErrorReporter.Logging,
    identity: BuildIdentity? = null,
    deadlines: ShutdownDeadlines = DEADLINES,
    authMethods: Scope.() -> List<AuthMethod>,
) {
    val config = loadConfig()
    val server =
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
            identity = identity,
        )

    // NOT `wait = true`. The main thread has to reach the await below; with `wait = true` the
    // signal arrives at a process that has no sequence to run, Ktor stops the public engine from
    // its own hook, and the management engine and the Koin container — which owns the database
    // pool — are never closed at all. From outside that is indistinguishable from a clean stop.
    server.start(wait = false)

    runBlocking {
        runUntilSignal(
            deadlines,
            // Inside the callback, not after the call: on the JVM this function returning means the
            // shutdown hook has returned and the runtime is already on its way out. Native carries
            // on, which is what makes the racing version easy to write and never notice.
            onFinished = { run -> println(run.transcript) },
        ) {
            // Readiness first, and this is the step shildik could not take before: `/ready` goes
            // false while the process can still finish what it holds, so the load balancer moves on
            // to the other replica instead of meeting a closed socket mid-sign-in.
            announce(AnnounceNotReady(server.readiness))

            // The public contour drains: in-flight token exchanges finish, new arrivals get 503.
            drain(EngineDrain(server.public, deadlines.drain, deadlines.drain + 5.seconds))

            // Management stops **after** the public contour and **before** the container, so no
            // probe can reach a repository whose pool has just been closed. It costs the kubelet a
            // few seconds of connection-refused at the very end, against a `/ready` that would
            // answer by throwing.
            consumer(EngineDrain(server.management, deadlines.drain, deadlines.drain + 5.seconds))

            // Last: the container owns the storage pool, and everything above it writes through it.
            // This is the close that `ApplicationStopping` would have run before the drain on
            // Kotlin/Native and after it on the JVM, from identical source.
            pool(participant("koin container") { server.closeContainer() })
        }
    }
}

/**
 * How the process stops, as numbers.
 *
 * 5 + 10 + 3×3 = 24 seconds inside a declared 30, which has to be the chart's
 * `terminationGracePeriodSeconds`: nothing tells a process its real budget on any platform, so kore
 * is *told* one and the chart has to keep saying the same number.
 *
 * **The pre-drain wait is kore's default five seconds and stays there**, unlike the single-replica
 * services in this portfolio. `charts/shildik` runs two replicas, so those five seconds are the
 * window in which traffic actually moves to the other pod rather than five seconds of downtime.
 */
private val DEADLINES =
    ShutdownDeadlines(
        preDrainWait = 5.seconds,
        drain = 10.seconds,
        releaseGroup = 3.seconds,
        gracePeriod = 30.seconds,
    )

/**
 * A participant out of a name and a lambda.
 *
 * `label` and `block` rather than `name` and `stop`: inside the object those two names belong to the
 * members being overridden, and `stop()` calling `stop` would be the function calling itself.
 */
private fun participant(
    label: String,
    block: suspend () -> Unit,
): ShutdownParticipant =
    object : ShutdownParticipant {
        override val name: String = label

        override suspend fun stop() {
            block()
        }
    }

/** The build version, for whatever a distribution attaches as observability. */
public val release: String get() = optional("SHILDIK_RELEASE") ?: "dev"

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
public expect fun optional(name: String): String?
