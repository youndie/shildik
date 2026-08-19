package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Откуда берётся адрес ключей.
 *
 * Раньше он собирался из известной формы пути, и это было верно ровно до тех пор, пока форма у
 * всех одна. Теперь она разная даже у нас — свои адреса и унаследованные от Keycloak, — поэтому
 * адрес спрашивается у провайдера, а собранный путь остаётся запасным.
 */
class EndpointAddressesTest {
    @Test
    fun `адрес ключей берётся из discovery`() =
        runTest {
            val provider = Provider("k1", "https://provider.test")
            // Провайдер держит ключи не там, где их собрал бы старый способ.
            provider.jwksUri = "https://provider.test/realms/main/oauth2/jwks"

            val addresses = EndpointAddresses(HttpClient(jwksEngine(provider)), provider.url, "main")

            assertEquals("https://provider.test/realms/main/oauth2/jwks", addresses.jwksUrl())
        }

    @Test
    fun `без discovery остаётся унаследованный адрес`() =
        runTest {
            val provider = Provider("k1", "https://provider.test")

            val addresses = EndpointAddresses(HttpClient(jwksEngine(provider)), provider.url, "main")

            // Именно keycloak-образный: у провайдера без discovery он скорее старый, чем новый.
            assertEquals(provider.certs, addresses.jwksUrl())
        }

    @Test
    fun `discovery спрашивается один раз`() =
        runTest {
            val provider = Provider("k1", "https://provider.test")
            provider.jwksUri = "https://provider.test/realms/main/oauth2/jwks"

            val addresses = EndpointAddresses(HttpClient(jwksEngine(provider)), provider.url, "main")
            repeat(5) { addresses.jwksUrl() }

            assertEquals(1, provider.discoveryCalls, "ответ обязан кэшироваться")
        }

    /**
     * Провайдер, лежавший в момент старта, не должен остаться «без discovery» навсегда: неудача
     * не запоминается, и после его возвращения адрес берётся оттуда, откуда положено.
     */
    @Test
    fun `неудача discovery не запоминается`() =
        runTest {
            val provider = Provider("k1", "https://provider.test")
            val addresses = EndpointAddresses(HttpClient(jwksEngine(provider)), provider.url, "main")

            assertEquals(provider.certs, addresses.jwksUrl())

            provider.jwksUri = "https://provider.test/realms/main/oauth2/jwks"

            assertEquals("https://provider.test/realms/main/oauth2/jwks", addresses.jwksUrl())
        }

    @Test
    fun `токен проверяется ключом с адреса из discovery`() =
        runTest {
            val provider = Provider("k1", "https://provider.test")
            provider.jwksUri = "https://provider.test/realms/main/oauth2/jwks"

            val client = HttpClient(jwksEngine(provider))
            val verifier = TokenVerifier(OidcConfig(realm = "main", url = provider.url).keys(client) {})

            assertNotNull(verifier.verify(provider.token()), "ключ не нашёлся по адресу из discovery")
        }
}
