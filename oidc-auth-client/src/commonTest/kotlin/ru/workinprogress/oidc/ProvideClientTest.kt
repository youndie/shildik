package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client to a neighbouring service: the token is attached on its own and renewed on a 401.
 *
 * What is under test is not Ktor but the joint with [OidcAuthService]. Tokens come from a fake
 * provider — a real one would go to the network, and it is not the subject here.
 */
class ProvideClientTest {
    private val config = OidcConfig(realm = "main", url = "https://provider.test", clientId = "billing", secret = "s")

    /**
     * The token provider: every call hands out the next token in sequence, so a test can see
     * which one a request went out with.
     *
     * `ContentNegotiation` is installed here rather than in the service: the service takes the
     * client as given, and parsing JSON is the client's job (see [OidcAuthService]).
     */
    private fun tokenClient(): HttpClient {
        var issued = 0
        val engine =
            MockEngine { request ->
                // Провайдер без discovery: этот тест про подстановку токена, а не про то, откуда
                // берётся адрес. Не ответить здесь 404 значит посчитать чтение discovery за
                // выдачу токена — и сбить нумерацию, по которой тест и различает токены.
                if ("openid-configuration" in request.url.toString()) {
                    return@MockEngine respondError(HttpStatusCode.NotFound)
                }
                issued++
                respond(
                    """{"access_token":"token-$issued","refresh_token":"refresh-$issued","expires_in":300,"token_type":"Bearer"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
    }

    @Test
    fun `a request to the neighbour goes out with a token`() =
        runTest {
            val auth = OidcAuthService(config, tokenClient())
            auth.requestNewTokens()

            val seen = mutableListOf<String>()
            val neighbour =
                MockEngine { request ->
                    seen += request.headers[HttpHeaders.Authorization].orEmpty()
                    respond("ok")
                }

            val client = provideClient(auth, config, endpoint = "orders-api:8080/internal", engine = neighbour)

            assertEquals("ok", client.request("/items").bodyAsText())
            assertEquals(listOf("Bearer token-1"), seen.toList())
        }

    @Test
    fun `a 401 fetches a new token and repeats the request`() =
        runTest {
            val auth = OidcAuthService(config, tokenClient())

            val seen = mutableListOf<String>()
            val neighbour =
                MockEngine { request ->
                    seen += request.headers[HttpHeaders.Authorization].orEmpty()
                    if (seen.size == 1) respondError(HttpStatusCode.Unauthorized) else respond("ok")
                }

            val client = provideClient(auth, config, endpoint = "orders-api:8080/internal", engine = neighbour)
            val response = client.request("/items")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, seen.size, "the request must be repeated after a 401")
            assertTrue(seen.last().startsWith("Bearer "), "the retry went out without a token: ${seen.last()}")
        }

    @Test
    fun `the address takes endpoint as given - port and path prefix included`() =
        runTest {
            val auth = OidcAuthService(config, tokenClient())
            auth.requestNewTokens()

            var url = ""
            val neighbour =
                MockEngine { request ->
                    url = request.url.toString()
                    respond("ok")
                }

            provideClient(
                auth,
                config,
                endpoint = "orders-api.orders.svc.cluster.local:8080/internal",
                engine = neighbour,
            ).request("/items")

            // Deployments are configured this way: the port and the path prefix live inside the
            // "host". It looks odd, it is what works in production, and this test holds it there.
            assertTrue("orders-api.orders.svc.cluster.local:8080/internal" in url, url)
            assertTrue(url.startsWith("http://"), url)
        }
}
