package ru.workinprogress.shildik.server

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
import ru.workinprogress.shildik.core.config.ShildikConfig
import ru.workinprogress.shildik.core.di.coreModule
import ru.workinprogress.shildik.core.di.domainModule
import ru.workinprogress.shildik.core.feature.admin.AdminAccess
import ru.workinprogress.shildik.server.admin.adminRoutes
import ru.workinprogress.shildik.server.oidc.oidcRoutes

/**
 * Two engines rather than one with two connectors.
 *
 * This way the management routes physically do not exist on the public port: a request to
 * `/admin/…` from outside gets a 404, not a 403 — the existence of a management contour is not
 * confirmed (api/endpoint-admin.md §4). With a shared engine and one routing tree this would have
 * to be enforced by a check on every route, that is, by hoping nobody forgets it.
 */
class ShildikServer(
    private val application: KoinApplication,
    private val public: EmbeddedServer<*, *>,
    private val management: EmbeddedServer<*, *>,
) {
    val koin: Koin get() = application.koin

    fun start(wait: Boolean = false) {
        management.start(wait = false)
        public.start(wait = wait)
    }

    fun stop() {
        public.stop()
        management.stop()
        // We close **our own** container, not the global one: other servers did not ask for it.
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
fun shildikServer(
    config: ShildikConfig,
    storage: Module,
    observability: Application.() -> Unit = {},
    reporter: ErrorReporter = ErrorReporter.Logging,
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

    return ShildikServer(
        application = application,
        public =
            embeddedServer(CIO, port = config.publicPort) {
                publicModule(koin)
                observability()
            },
        management = embeddedServer(CIO, port = config.managementPort) { managementModule(koin) },
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
fun Application.publicModule(koin: Koin) {
    commonPlugins()
    healthRoutes(koin.get())
    oidcRoutes(koin)
}

/**
 * The management contour. It has lived on a separate port since M0 so that the admin API appeared
 * in its final place instead of moving there later — a move would have meant that for some time the
 * management handles lived on the public port.
 */
fun Application.managementModule(koin: Koin) {
    commonPlugins()
    healthRoutes(koin.get())
    adminRoutes(koin)
}
