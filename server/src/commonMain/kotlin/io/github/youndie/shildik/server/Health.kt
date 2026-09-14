package io.github.youndie.shildik.server

import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.shildik.core.port.StorageHealth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
public data class HealthResponse(
    val status: String,
)

/**
 * Two probes, and they answer **different** questions.
 *
 * `/health` is about the process being alive. It deliberately does not touch the database: hang
 * liveness on it and an unreachable database starts restarting every pod at once, stretching the
 * recovery across restarts instead of letting it happen by itself.
 *
 * `/ready` is about being able to serve. Here the database is **mandatory**: without it the service
 * hands out neither tokens nor keys, and it has no business staying in the endpoints.
 *
 * The split did not come from a principle. On native, a vanished Postgres made a database query
 * hang without an answer while `/health` kept answering 200: the pod counted as healthy while
 * serving nothing (BACKLOG M-69).
 *
 * **`/ready` now has a second reason to say no, and it is the one this service could not say
 * before: "I am stopping".** kore's [ReadinessGate] is a one-way latch flipped by the announce
 * stage of the shutdown sequence, and readiness going false *before* anything closes is what takes
 * the pod out of the endpoints while it can still finish what it is holding. Without it a draining
 * provider went on being sent sign-ins until the socket went away, and the client saw a connection
 * reset rather than a load balancer moving on.
 *
 * The gate carries no dependency checks of its own here: the storage check below is shildik's and
 * stays exactly as it was, uncached, because a cached answer about the database is a different
 * promise from the one this endpoint has been making.
 */
public fun Application.healthRoutes(
    storage: StorageHealth,
    readiness: ReadinessGate,
) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse("ok"))
        }

        get("/ready") {
            when {
                readiness.isShuttingDown -> {
                    call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("shutting down"))
                }

                storage.check() -> {
                    call.respond(HttpStatusCode.OK, HealthResponse("ok"))
                }

                else -> {
                    call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("storage unavailable"))
                }
            }
        }
    }
}
