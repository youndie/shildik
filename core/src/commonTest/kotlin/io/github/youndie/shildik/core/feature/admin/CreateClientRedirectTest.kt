package io.github.youndie.shildik.core.feature.admin

import io.github.youndie.shildik.core.feature.browser.DirectTransactions
import io.github.youndie.shildik.core.feature.browser.FakeClients
import io.github.youndie.shildik.core.feature.browser.FakeTenants
import io.github.youndie.shildik.core.model.Tenant
import io.github.youndie.shildik.core.model.TenantId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Which return addresses an app may register (RFC 8252 §7.1).
 *
 * The operating system hands a private-use scheme to whichever installed app claimed it. A scheme
 * nobody else has a reason to claim — a reversed domain name — is the only protection there is, so
 * a short one is refused when the client is created rather than discovered after a code went to
 * somebody else's app.
 */
class CreateClientRedirectTest {
    private suspend fun create(
        redirectUri: String,
        public: Boolean = true,
    ) = CreateClientUseCase(
        FakeTenants(listOf(Tenant(TenantId("t1"), "main", true))),
        FakeClients(),
        DirectTransactions(),
    )(
        CreateClientUseCase.Params(
            realm = "main",
            clientId = "app",
            roles = emptySet(),
            public = public,
            redirectUris = setOf(redirectUri),
        ),
    )

    @Test
    fun `a reversed domain name scheme is accepted in both spellings`() =
        runTest {
            for (uri in listOf("io.example.app:/oauth2/callback", "io.example.app://callback")) {
                assertTrue(create(uri).isSuccess, uri)
            }
        }

    @Test
    fun `a scheme any app could claim is refused for a public client`() =
        runTest {
            val result = create("myapp://callback")

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("RFC 8252"),
                "the refusal has to say why: ${result.exceptionOrNull()?.message}",
            )
        }

    @Test
    fun `web and loopback addresses are not affected`() =
        runTest {
            for (uri in listOf("https://app.example.com/cb", "http://127.0.0.1/cb", "HTTPS://app.example.com/cb")) {
                assertTrue(create(uri).isSuccess, uri)
            }
        }

    @Test
    fun `a confidential client is not an app and is not checked`() =
        runTest {
            assertTrue(create("myapp://callback", public = false).isSuccess)
        }
}
