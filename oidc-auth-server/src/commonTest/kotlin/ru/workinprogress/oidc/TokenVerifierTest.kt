package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.crypto.SigningKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The signature and lifetime checks — what an outside library used to do and this code does now.
 *
 * Hence what is in here: not "does a good token work" — that shows up anywhere — but **is a bad
 * one rejected**. A hole in verification looks exactly like a green test until the day someone
 * uses it.
 */
class TokenVerifierTest {
    private val provider = Provider("provider-key", "https://provider.test")

    private fun verifier(vararg providers: Provider): TokenVerifier {
        val client = HttpClient(jwksEngine(*providers))
        return TokenVerifier(JwksSource(client, providers.first().certs))
    }

    @Test
    fun `a valid token is accepted and its claims are parsed`() =
        runTest {
            val verified =
                verifier(provider).verify(
                    provider.token(clientId = "billing", roles = setOf("a", "b"), email = "someone@example.com"),
                )

            assertNotNull(verified)
            assertEquals("billing", verified.azp)
            assertEquals(setOf("a", "b"), verified.roles)
            assertEquals("someone@example.com", verified.email)
            assertEquals("service-account-billing", verified.subject)
        }

    @Test
    fun `an expired token is rejected`() =
        runTest {
            val token = provider.token(expiresIn = 5.minutes)

            // The verifier's clock is moved rather than the token: that is how it looks in production.
            val late =
                TokenVerifier(
                    JwksSource(HttpClient(jwksEngine(provider)), provider.certs),
                    now = { Clock.System.now() + 2.hours },
                )

            assertNull(late.verify(token))
        }

    @Test
    fun `a token without a lifetime is rejected`() =
        runTest {
            assertNull(verifier(provider).verify(provider.tokenWithoutExpiry()))
        }

    @Test
    fun `a token signed with a foreign key is rejected`() =
        runTest {
            // A key with the **same** kid as the provider's: if only the header were checked and
            // not the signature, this token would get through.
            val stranger = SigningKey.generate(provider.kid)

            assertNull(verifier(provider).verify(provider.token(signWith = stranger)))
        }

    @Test
    fun `forged claims are rejected`() =
        runTest {
            val token = provider.token(clientId = "billing", roles = setOf("orders:read"))
            val parts = token.split(".")
            val forged = parts[0] + "." + parts[1].dropLast(4) + "AAAA." + parts[2]

            assertNull(verifier(provider).verify(forged))
        }

    @Test
    fun `a token with an unknown kid is rejected outright`() =
        runTest {
            val other = Provider("another-key", "https://provider.test")

            assertNull(verifier(provider).verify(other.token()))
        }

    @Test
    fun `garbage instead of a token does not break verification`() =
        runTest {
            val verifier = verifier(provider)

            assertNull(verifier.verify(""))
            assertNull(verifier.verify("not.a.token"))
            assertNull(verifier.verify("a.b.c"))
        }
}
