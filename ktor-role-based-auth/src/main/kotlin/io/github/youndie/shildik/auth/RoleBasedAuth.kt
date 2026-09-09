package io.github.youndie.shildik.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authentication
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

public typealias Role = String

/**
 * What the plugin asks of a principal, and the whole of it.
 *
 * Authorization deliberately knows nothing about how the caller was authenticated. A principal
 * that can name its roles is enough — whether it came from a JWT, a session or an API key is the
 * authentication provider's business.
 */
public interface RoleBasedPrincipal {
    public val roles: Set<Role>
}

/** How the required roles are matched against the ones the caller actually has. */
public enum class AuthType {
    /** Every required role must be present. */
    ALL,

    /** At least one of the required roles must be present. */
    ANY,

    /** None of the listed roles may be present. */
    NONE,
}

public class RoleBasedAuthConfiguration {
    public var requiredRoles: Set<Role> = emptySet()
    public var authType: AuthType = AuthType.ALL
}

/** Why a request was refused. Read it from `call.attributes` in a status page or a log. */
public val AuthorizationErrorKey: AttributeKey<List<String>> = AttributeKey("AuthorizationError")

/**
 * Route-scoped authorization: it runs after authentication and answers 403 when the roles do not
 * match.
 *
 * A caller with no [RoleBasedPrincipal] is **not** refused here. That is not an oversight: an
 * unauthenticated request is the authentication provider's business, and a provider that
 * challenges has already answered 401 by this point. Refusing again here would turn every
 * missing-authentication case into a 403, which reads as "you are known and not allowed" instead
 * of "you are not known".
 *
 * The sharp edge that follows: under `authenticate(optional = true)` nothing challenges, no
 * principal appears, and the request goes through the role check untouched. Roles guard what a
 * **known** caller may do; whether an unknown caller is let in at all is what `optional` decides.
 * There is a test pinning this, so it is a documented property rather than a surprise.
 */
public val RoleBasedAuthorization: RouteScopedPlugin<RoleBasedAuthConfiguration> =
    createRouteScopedPlugin(
        name = "RoleBasedAuthorization",
        createConfiguration = ::RoleBasedAuthConfiguration,
    ) {
        on(AuthenticationChecked) { call ->
            val principal = call.authentication.principal<RoleBasedPrincipal>() ?: return@on

            val present = principal.roles
            val required = pluginConfig.requiredRoles
            val denied = mutableListOf<String>()

            when (pluginConfig.authType) {
                AuthType.ALL -> {
                    val missing = required - present
                    if (missing.isNotEmpty()) {
                        denied += "Missing roles: ${missing.joinToString(", ")}"
                    }
                }

                AuthType.ANY -> {
                    if (required.isNotEmpty() && present.none { it in required }) {
                        denied += "No matching roles. Required one of: ${required.joinToString(", ")}"
                    }
                }

                AuthType.NONE -> {
                    val forbidden = present intersect required
                    if (forbidden.isNotEmpty()) {
                        denied += "Has forbidden roles: ${forbidden.joinToString(", ")}"
                    }
                }
            }

            if (denied.isNotEmpty()) {
                call.attributes.put(AuthorizationErrorKey, denied)
                call.respond(HttpStatusCode.Forbidden)
            }
        }
    }

/** Routes inside require [role]. */
public fun Route.withRole(
    role: Role,
    build: Route.() -> Unit,
): Route = withRoles(role, build = build)

/** Routes inside require **all** of [roles]. */
public fun Route.withRoles(
    vararg roles: Role,
    build: Route.() -> Unit,
): Route = authorizedRoute(roles.toSet(), AuthType.ALL, build)

/** Routes inside require **any one** of [roles]. */
public fun Route.withAnyRole(
    vararg roles: Role,
    build: Route.() -> Unit,
): Route = authorizedRoute(roles.toSet(), AuthType.ANY, build)

/** Routes inside are refused to anyone holding any of [roles]. */
public fun Route.withoutRoles(
    vararg roles: Role,
    build: Route.() -> Unit,
): Route = authorizedRoute(roles.toSet(), AuthType.NONE, build)

/**
 * The selector exists for the route tree to be readable: without it every authorized route prints
 * as an anonymous child, and `/orders` guarded by two different role sets looks like one route
 * twice.
 */
public class AuthorizedRouteSelector(
    private val description: String,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Constant

    override fun toString(): String = "Authorized(roles=$description)"
}

private fun Route.authorizedRoute(
    roles: Set<Role>,
    type: AuthType,
    build: Route.() -> Unit,
): Route {
    val authorized = createChild(AuthorizedRouteSelector(roles.joinToString(",")))
    authorized.install(RoleBasedAuthorization) {
        requiredRoles = roles
        authType = type
    }
    authorized.build()
    return authorized
}
