package ru.workinprogress.shildik.server.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.server.response.respond
import ru.workinprogress.shildik.core.feature.admin.AdminAccess
import ru.workinprogress.shildik.shared.ErrorView

class AdminAuthConfig {
    var access: AdminAccess? = null
}

/**
 * The pass into the management contour.
 *
 * A plugin over the whole `/admin` branch rather than a check in every handler: forgetting it on
 * one route is a matter of time, and the price of forgetting is at its highest here. The same
 * device as `RoleBasedAuthorization` uses.
 *
 * A separate port is the first line (api/endpoint-admin.md §1) but not the only one: a pod in the
 * same namespace can reach it, so a token is needed as well.
 */
val AdminAuth =
    createRouteScopedPlugin("AdminAuth", ::AdminAuthConfig) {
        val access = requireNotNull(pluginConfig.access) { "AdminAuth without AdminAccess" }

        onCall { call ->
            val presented =
                call.request
                    .header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.trim()
                    ?: call.request.header("X-Shildik-Token")

            if (!access.accepts(presented)) {
                // Neither a reason nor a hint: "wrong token" and "bootstrap already closed" look
                // the same from outside.
                call.respond(HttpStatusCode.Unauthorized, ErrorView("unauthorized"))
            }
        }
    }
