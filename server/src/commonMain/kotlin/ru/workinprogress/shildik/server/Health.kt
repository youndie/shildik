package ru.workinprogress.shildik.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import ru.workinprogress.shildik.core.port.StorageHealth

@Serializable
data class HealthResponse(
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
 */
fun Application.healthRoutes(storage: StorageHealth) {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse("ok"))
        }

        get("/ready") {
            if (storage.check()) {
                call.respond(HttpStatusCode.OK, HealthResponse("ok"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("storage unavailable"))
            }
        }
    }
}
