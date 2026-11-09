package io.github.youndie.shildik.server

import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.ktor.startForKore
import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.version.BuildIdentity
import io.github.youndie.shildik.core.config.ShildikConfig
import io.github.youndie.shildik.core.di.coreModule
import io.github.youndie.shildik.core.di.domainModule
import io.github.youndie.shildik.core.feature.admin.AdminAccess
import io.github.youndie.shildik.server.admin.adminRoutes
import io.github.youndie.shildik.server.oidc.oidcRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Two engines rather than one with two connectors.
 *
 * This way the management routes physically do not exist on the public port: a request to
 * `/admin/…` from outside gets a 404, not a 403 — the existence of a management contour is not
 * confirmed (api/endpoint-admin.md §4). With a shared engine and one routing tree this would have
 * to be enforced by a check on every route, that is, by hoping nobody forgets it.
 */
public class ShildikServer(
    private val application: KoinApplication,
    public val public: EmbeddedServer<*, *>,
    public val management: EmbeddedServer<*, *>,
    /**
     * The latch `/ready` reads, and the one the announce stage of an ordered shutdown flips.
     *
     * Exposed rather than hidden because the caller owns the order: `:server-boot` hands it to
     * kore's announce stage, and a consumer assembling its own shutdown needs the same handle.
     */
    public val readiness: ReadinessGate = ReadinessGate(),
    /**
     * The latch the public contour's shutdown refusal reads, and the one the drain stage opens.
     *
     * **Not [readiness].** Readiness falls when the announce begins, and the announce exists to go
     * on *serving* while the news that this pod is leaving reaches the load balancer: a sign-in that
     * arrives in those seconds was routed here by a node that has not heard yet, and answering it is
     * the point of waiting. Only the drain refuses. Gating the refusal on readiness — what this
     * server did until kore B-61 — answered `503` to every request of the five-second announce.
     *
     * The caller hands **this instance** to kore's `EngineDrain` for [public]; a fresh gate there
     * compiles and never opens the refusal.
     */
    public val draining: DrainGate = DrainGate(),
) {
    public val koin: Koin get() = application.koin

    /**
     * Starts both engines the way kore's ordered shutdown needs them started: Ktor's own shutdown
     * hook off, and `wait = false`.
     *
     * On the JVM Ktor's hook is a `Runtime` hook the JVM runs concurrently with kore's, and it stops
     * the engine the moment `SIGTERM` lands — while the announce is still running, so readiness
     * becomes a refused connection instead of a `503`; `EngineDrain` refuses to be built beside it.
     * On Kotlin/Native it is the window between Ktor installing its signal handler and kore taking
     * the signal, in which a `SIGTERM` hung the process (kore B-63). Ktor reads the switch on the
     * first `start()` in the process, so **management goes through this too**, not only the
     * contour that drains.
     *
     * A process started this way has to go on to kore's `runUntilSignal`: on native kore's handler
     * only records the signal, and nothing else stops the engines.
     */
    public fun startForKore() {
        management.startForKore()
        public.startForKore()
    }

    /**
     * Starts both engines with Ktor's own shutdown handling, for callers that have no ordered
     * shutdown — tests, a local run. `:server-boot` uses [startForKore].
     */
    public fun start(wait: Boolean = false) {
        management.start(wait = false)
        public.start(wait = wait)
    }

    /**
     * Stops both engines and closes the container.
     *
     * **Unordered, and kept for callers that have nothing better** — tests, a local run, a consumer
     * that is not in Kubernetes. `:server-boot` does not use it: a process that is asked to stop
     * has to announce first, then drain, then close the pool, and this method does all of it at
     * once because it cannot know how long it is allowed to take.
     */
    public fun stop() {
        public.stop()
        management.stop()
        // We close **our own** container, not the global one: other servers did not ask for it.
        application.close()
    }

    /** Closes the container. The pool goes with it, so this belongs *after* the engines have drained. */
    public fun closeContainer() {
        application.close()
    }
}

/**
 * Dependencies are handed to the routes **explicitly** rather than resolved inside from a global
 * context.
 *
 * The `install(Koin)` plugin is deliberately not used here: it raises its own container per Ktor
 * application, and we have two of them while the graph has to stay single. Passing them explicitly
 * also removes the question "which container did this come from" in tests.
 *
 * @param observability is attached to the public contour. It is a parameter because it lives in
 *   `jvmMain` (katcher and metrik are JVM libraries) while the server is assembled in shared code.
 */
public fun shildikServer(
    config: ShildikConfig,
    storage: Module,
    observability: Application.() -> Unit = {},
    reporter: ErrorReporter = ErrorReporter.Logging,
    /**
     * What `/version` reports, or `null` for no such route.
     *
     * Passed in rather than read here because the object is **generated per module** by the
     * `io.github.youndie.kore.build` plugin, and the module that has it is the distribution — the
     * one that becomes a binary. A `:server` that imported a generated symbol would be a library
     * that cannot be compiled without the plugin applied to itself.
     */
    identity: BuildIdentity? = null,
): ShildikServer {
    // An **isolated** container, not the global `startKoin`. The global one made two servers in
    // one JVM impossible: the second failed with `KoinApplicationAlreadyStarted`, and `stopKoin` on
    // one tore the container out from under another that was still alive. A suite where every class
    // raises its own server failed from this every other run — and the failure looked random
    // (BACKLOG M-47).
    val application =
        koinApplication {
            modules(coreModule(config), domainModule(), storage, module { single { reporter } })
        }
    val koin = application.koin

    // Printed on **every** start while there is no administrator: the pod can restart before the
    // first one has been created, and the token from an earlier log no longer works (research §R8).
    runBlocking {
        if (koin.get<AdminAccess>().isBootstrapPhase()) {
            log.info(
                "no administrators yet, the management contour accepts the bootstrap token: " +
                    config.effectiveBootstrapToken,
            )
        }
    }

    val readiness = ReadinessGate()
    val draining = DrainGate()

    return ShildikServer(
        application = application,
        public =
            embeddedServer(CIO, port = config.publicPort) {
                publicModule(koin, readiness, draining, identity)
                observability()
            },
        management =
            embeddedServer(CIO, port = config.managementPort) {
                managementModule(koin, readiness, identity)
            },
        readiness = readiness,
        draining = draining,
    )
}

private fun Application.commonPlugins() {
    // Type-safe URLs from `:shared` — one description of the wire for server and client.
    install(Resources)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                // Required: `token_type` and the lists in discovery are default values, and
                // kotlinx **does not write** defaults. Without this line the token response arrives
                // without `token_type`, and a platform `TokenResponse` where the field is mandatory
                // fails while parsing. Only a test against a real client catches this.
                encodeDefaults = true
            },
        )
    }
}

/** The public contour: token, certs, discovery and health. No management handles here. */
public fun Application.publicModule(
    koin: Koin,
    readiness: ReadinessGate = ReadinessGate(),
    /** The same instance the drain stage opens — see [ShildikServer.draining]. */
    draining: DrainGate = DrainGate(),
    identity: BuildIdentity? = null,
) {
    // BEFORE the routes. An interceptor installed later would let through every call that arrived
    // first, and the one request this must not miss is the first sign-in after the drain began.
    // kore's own paths stay served — a 503 from a liveness probe restarts the pod in the middle of
    // the shutdown it is reporting — and so does `/ready`, which is *supposed* to fail and says so
    // in its own words.
    //
    // On the DRAIN, not on readiness. This line read `{ readiness.isShuttingDown }` until kore B-61
    // and refused every sign-in from the first millisecond of the announce — the five seconds in
    // which the other replica is still being told to take over, and this one is meant to serve.
    installShutdownRefusal(draining)
    commonPlugins()
    healthRoutes(koin.get(), readiness)
    identity?.let { installKoreVersion(it) }
    oidcRoutes(koin)
}

/**
 * The management contour. It has lived on a separate port since M0 so that the admin API appeared
 * in its final place instead of moving there later — a move would have meant that for some time the
 * management handles lived on the public port.
 */
public fun Application.managementModule(
    koin: Koin,
    readiness: ReadinessGate = ReadinessGate(),
    identity: BuildIdentity? = null,
) {
    // No shutdown refusal here on purpose: the management contour is where the probes live, and it
    // has to keep answering for as long as the process does. It is also where an operator looks
    // while a shutdown is happening.
    commonPlugins()
    healthRoutes(koin.get(), readiness)
    identity?.let { installKoreVersion(it) }
    adminRoutes(koin)
}
