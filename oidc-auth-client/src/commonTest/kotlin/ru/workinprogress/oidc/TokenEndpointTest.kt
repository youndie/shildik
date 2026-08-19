package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Куда клиент идёт за токеном.
 *
 * Адрес больше не собирается из известной формы пути: он читается из discovery, а собранный
 * остаётся запасным. Проверяется именно это — что провайдер, держащий токен-эндпоинт не там,
 * где его собрал бы старый способ, всё равно обслуживается.
 */
class TokenEndpointTest {
    private val config = OidcConfig(realm = "main", url = "https://provider.test", clientId = "billing", secret = "s")

    private val discovery = "https://provider.test/realms/main/.well-known/openid-configuration"
    private val legacy = "https://provider.test/realms/main/protocol/openid-connect/token"
    private val fresh = "https://provider.test/realms/main/oauth2/token"

    private fun client(
        tokenEndpoint: String?,
        seen: MutableList<String>,
    ): HttpClient {
        val engine =
            MockEngine { request ->
                val address = request.url.toString()
                when {
                    address == discovery && tokenEndpoint == null -> respondError(HttpStatusCode.NotFound)

                    address == discovery ->
                        respond(
                            """{"issuer":"https://provider.test/realms/main","token_endpoint":"$tokenEndpoint"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )

                    else -> {
                        seen += address
                        respond(
                            """{"access_token":"t","expires_in":300,"token_type":"Bearer"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
        return HttpClient(engine) { install(ContentNegotiation) { json() } }
    }

    @Test
    fun `за токеном идём по адресу из discovery`() =
        runTest {
            val seen = mutableListOf<String>()

            OidcAuthService(config, client(fresh, seen)).requestNewTokens()

            assertEquals(listOf(fresh), seen)
        }

    @Test
    fun `без discovery остаётся унаследованный адрес`() =
        runTest {
            val seen = mutableListOf<String>()

            OidcAuthService(config, client(null, seen)).requestNewTokens()

            assertEquals(listOf(legacy), seen)
        }
}
