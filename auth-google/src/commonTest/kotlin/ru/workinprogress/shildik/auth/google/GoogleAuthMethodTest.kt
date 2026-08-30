package ru.workinprogress.shildik.auth.google

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in through Google. The real Google is not needed: our behaviour is what is tested. */
class GoogleAuthMethodTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val callback = "https://id.example.com/realms/main/protocol/openid-connect/auth/google/callback"

    private fun method(engine: MockEngine) =
        GoogleAuthMethod(
            clientId = "google-client",
            clientSecret = "google-secret",
            engine = engine,
            tokenEndpoint = "https://oauth2.example/token",
            userInfoEndpoint = "https://openid.example/userinfo",
        )

    private fun happyEngine() =
        MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/token") -> {
                    respond("""{"access_token":"at-1"}""", HttpStatusCode.OK, json)
                }

                else -> {
                    respond(
                        """{"sub":"117000","email":"owner@example.com","name":"Owner"}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
            }
        }

    private fun callbackRequest(vararg extra: Pair<String, String>) =
        AuthRequest(
            realm = "main",
            parameters = mapOf("code" to "google-code", GoogleAuthMethod.CALLBACK_URI_PARAM to callback) + extra,
        )

    @Test
    fun `the sign-in address carries everything Google needs`() {
        val url = Url(method(happyEngine()).authorizationUrl(callback, "state-1"))

        assertEquals("code", url.parameters["response_type"])
        assertEquals("google-client", url.parameters["client_id"])
        assertEquals(callback, url.parameters["redirect_uri"])
        assertEquals("state-1", url.parameters["state"])
        assertTrue("openid" in url.parameters["scope"].orEmpty())
    }

    @Test
    fun `the identity comes from sub and not from email`() =
        runTest {
            val subject = method(happyEngine()).authenticate(callbackRequest())

            // People change emails; binding by one would mean that changing an address creates a
            // new user and takes their shops away from the old one.
            assertEquals("117000", subject?.externalId)
            assertEquals("owner@example.com", subject?.email)
        }

    @Test
    fun `a refusal by the person is not a server error`() =
        runTest {
            val subject = method(happyEngine()).authenticate(callbackRequest("error" to "access_denied"))

            assertNull(subject, "the person pressed cancel — that is not a failure")
        }

    @Test
    fun `no token from the provider means no identity`() =
        runTest {
            val engine =
                MockEngine { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json) }

            assertNull(method(engine).authenticate(callbackRequest()))
        }

    @Test
    fun `no profile from the provider means no identity`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/token")) {
                        respond("""{"access_token":"at-1"}""", HttpStatusCode.OK, json)
                    } else {
                        respond("""{"error":"forbidden"}""", HttpStatusCode.Forbidden, json)
                    }
                }

            // Quietly returning "signed in" with an empty sub would be the worst outcome.
            assertNull(method(engine).authenticate(callbackRequest()))
        }
}
