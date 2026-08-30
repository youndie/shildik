package ru.workinprogress.shildik.cli

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading users out of the previous provider.
 *
 * The point here is **paging**. Keycloak has its own limit on `/users`, and a request without
 * pages returns only the beginning of the list without saying so: the import reports success
 * while half the people stay with the old provider. Such a mistake is invisible until somebody
 * tries to sign in.
 */
class KeycloakSourceTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun user(id: String) =
        """{"id":"$id","email":"$id@example.com","firstName":"Ada","lastName":"Lovelace",
           "enabled":true,"emailVerified":true}"""

    @Test
    fun `reads every page rather than only the first`() =
        runTest {
            val requestedOffsets = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/token") -> {
                            respond("""{"access_token":"t"}""", HttpStatusCode.OK, json)
                        }

                        path.endsWith("/federated-identity") -> {
                            respond("""[{"identityProvider":"google","userId":"g-1"}]""", HttpStatusCode.OK, json)
                        }

                        else -> {
                            val first = request.url.parameters["first"]
                            requestedOffsets += first.orEmpty()
                            val page =
                                when (first.orEmpty()) {
                                    "0" -> (1..2).joinToString(",") { user("u$it") }
                                    "2" -> user("u3")
                                    else -> ""
                                }
                            respond(content = "[$page]", status = HttpStatusCode.OK, headers = json)
                        }
                    }
                }

            val users = source(engine).users(pageSize = 2)

            assertEquals(listOf("u1", "u2", "u3"), users.map { it.id })
            assertEquals(listOf("0", "2"), requestedOffsets, "the second page has to be requested")
        }

    @Test
    fun `carries over the external provider identity`() =
        runTest {
            val users = source(singleUserEngine()).users()

            val identity = users.single().identities.single()
            assertEquals("google", identity.provider)
            assertEquals("g-1", identity.subject)
        }

    @Test
    fun `the name is assembled from two fields`() =
        runTest {
            assertEquals("Ada Lovelace", source(singleUserEngine()).users().single().name)
        }

    @Test
    fun `a refusal from the provider does not turn into an empty list`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/token")) {
                        respond("""{"access_token":"t"}""", HttpStatusCode.OK, json)
                    } else {
                        respond("""{"error":"forbidden"}""", HttpStatusCode.Forbidden, json)
                    }
                }

            val failure = runCatching { source(engine).users() }.exceptionOrNull()

            // An empty list would mean "nobody to import" — the quietest way to lose everyone.
            assertTrue(failure is AdminApiException, "expected an error, got: $failure")
            assertEquals(403, failure.status)
        }

    private fun singleUserEngine() =
        MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/token") -> {
                    respond("""{"access_token":"t"}""", HttpStatusCode.OK, json)
                }

                path.endsWith("/federated-identity") -> {
                    respond("""[{"identityProvider":"google","userId":"g-1"}]""", HttpStatusCode.OK, json)
                }

                else -> {
                    respond("[${user("u1")}]", HttpStatusCode.OK, json)
                }
            }
        }

    private fun source(engine: MockEngine) =
        KeycloakSource(
            baseUrl = "https://auth.example",
            realm = "main",
            clientId = "billing",
            clientSecret = "s",
            engine = engine,
        )
}
