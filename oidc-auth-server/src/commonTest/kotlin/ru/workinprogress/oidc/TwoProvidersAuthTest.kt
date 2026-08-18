package ru.workinprogress.oidc

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Accepting two providers at once, end to end.
 *
 * What is under test is not that [MultiSourceKeys] can search a list — that has its own test — but
 * that a service configured this way **actually admits** the holder of a token from the second
 * provider while still admitting one from the first.
 */
class TwoProvidersAuthTest {
    private val legacy = Provider("legacy-key", "https://legacy.test")
    private val newcomer = Provider("provider-key", "https://provider.test")

    private fun Application.protectedRoute(config: OidcConfig) {
        configureAuth(config, engine = jwksEngine(legacy, newcomer)) { (roles, _, _) ->
            "orders:read" in roles
        }
        routing {
            authenticate(JWT_AUTH_OIDC) {
                get("/protected") { call.respondText(call.principal<OidcPrincipal>()?.azp.orEmpty()) }
            }
        }
    }

    @Test
    fun `the service accepts tokens from both providers`() =
        testApplication {
            application {
                protectedRoute(
                    OidcConfig(realm = "main", url = legacy.url, additionalUrl = newcomer.url),
                )
            }

            assertEquals(
                HttpStatusCode.OK,
                client.get("/protected") { bearerAuth(legacy.token()) }.status,
                "a token from the previous provider must keep working",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/protected") { bearerAuth(newcomer.token()) }.status,
                "this is the point of it: a token from the new provider is accepted too",
            )
        }

    @Test
    fun `without the second address a token from the new provider is rejected`() =
        testApplication {
            application { protectedRoute(OidcConfig(realm = "main", url = legacy.url)) }

            // The default state: while `additionalUrl` is blank the behaviour is unchanged —
            // otherwise every service would silently start accepting a second provider.
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/protected") { bearerAuth(newcomer.token()) }.status,
            )
        }

    @Test
    fun `the role is checked and not only the signature`() =
        testApplication {
            application { protectedRoute(OidcConfig(realm = "main", url = legacy.url)) }

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/protected") { bearerAuth(legacy.token(roles = setOf("nothing"))) }.status,
            )
        }

    @Test
    fun `no authorization header means 401`() =
        testApplication {
            application { protectedRoute(OidcConfig(realm = "main", url = legacy.url)) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/protected").status)
        }

    @Test
    fun `a configuration without the second address parses from json as before`() {
        val config = Json.decodeFromString<OidcConfig>("""{"realm":"main","url":"http://x"}""")

        assertEquals("", config.additionalUrl)
    }
}
