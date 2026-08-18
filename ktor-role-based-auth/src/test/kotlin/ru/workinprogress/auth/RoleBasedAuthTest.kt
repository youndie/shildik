package ru.workinprogress.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

private class TestPrincipal(
    override val roles: Set<Role>,
) : RoleBasedPrincipal

/** An authentication provider that hands out whatever roles the test asks for. */
private class Stub(
    config: Config,
) : AuthenticationProvider(config) {
    class Config(
        val roles: Set<Role>?,
    ) : AuthenticationProvider.Config("stub")

    private val roles = config.roles

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val granted = roles
        if (granted == null) {
            // A real provider challenges when there are no credentials. The stub does the same,
            // because the point of the test below is which of the two — authentication or
            // authorization — answers.
            context.challenge("stub", AuthenticationFailedCause.NoCredentials) { challenge, call ->
                call.respond(HttpStatusCode.Unauthorized)
                challenge.complete()
            }
            return
        }
        context.principal(TestPrincipal(granted))
    }
}

private fun AuthenticationConfig.stub(roles: Set<Role>?) {
    register(Stub(Stub.Config(roles)))
}

class RoleBasedAuthTest {
    private fun server(
        roles: Set<Role>?,
        route: Route.() -> Unit,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(Authentication) { stub(roles) }
            routing { authenticate("stub") { route() } }
        }
        block(createClient { })
    }

    @Test
    fun `all required roles present - allowed`() =
        server(setOf("orders:read", "orders:write"), {
            withRoles("orders:read", "orders:write") { get("/x") { call.respondText("ok") } }
        }) { client ->
            assertEquals(HttpStatusCode.OK, client.get("/x").status)
        }

    @Test
    fun `one required role missing - forbidden`() =
        server(setOf("orders:read"), {
            withRoles("orders:read", "orders:write") { get("/x") { call.respondText("ok") } }
        }) { client ->
            assertEquals(HttpStatusCode.Forbidden, client.get("/x").status)
        }

    @Test
    fun `any of the roles is enough`() =
        server(setOf("billing:read"), {
            withAnyRole("orders:read", "billing:read") { get("/x") { call.respondText("ok") } }
        }) { client ->
            assertEquals(HttpStatusCode.OK, client.get("/x").status)
        }

    @Test
    fun `a forbidden role denies access`() =
        server(setOf("suspended"), {
            withoutRoles("suspended") { get("/x") { call.respondText("ok") } }
        }) { client ->
            assertEquals(HttpStatusCode.Forbidden, client.get("/x").status)
        }

    /**
     * Without a principal the answer is 401 and not 403 — authorization stays out of it. If the
     * plugin refused here, a request with no credentials would read as "you are known and not
     * allowed" instead of "you are not known".
     */
    @Test
    fun `no principal - unauthorized rather than forbidden`() =
        server(null, {
            withRole("orders:read") { get("/x") { call.respondText("ok") } }
        }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/x").status)
        }

    /**
     * The sharp edge that follows from the rule above, pinned by a test so nobody discovers it in
     * production: under `authenticate(optional = true)` nothing challenges, no principal appears,
     * and the role check lets the request through.
     *
     * Roles guard what a **known** caller may do. Whether an unknown caller is allowed in at all
     * is the authentication provider's decision, and `optional = true` is that decision.
     */
    @Test
    fun `optional authentication lets an anonymous caller past the role check`() =
        testApplication {
            application {
                install(Authentication) { stub(null) }
                routing {
                    authenticate("stub", optional = true) {
                        withRole("orders:read") { get("/x") { call.respondText("ok") } }
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, createClient { }.get("/x").status)
        }
}
