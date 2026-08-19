package ru.workinprogress.shildik.auth.magic

import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MagicLinkAuthMethodTest {
    // The token was produced by another implementation (python hmac) in the shape the sender
    // uses: email/iat/exp claims, HS256. Checking our own token with our own code proves nothing.
    private val secret = "handoff-test-secret"
    private val token =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9" +
            ".eyJlbWFpbCI6Im93bmVyQGV4YW1wbGUuY29tIiwiaWF0IjoxNzg1MDAwMDAwLCJleHAiOjE3ODUwMDAwMzB9" +
            ".gdL3YGBunVvm4TFSV8ZCpQVZBwAKfR1ycnTovahI82Y"

    /** A moment inside the token's lifetime: `exp` = 1785000030. */
    private fun clockAt(epochSeconds: Long) =
        object : Clock {
            override fun now() = Instant.fromEpochSeconds(epochSeconds)
        }

    private fun method(epochSeconds: Long = 1785000010) = MagicLinkAuthMethod(secret, clockAt(epochSeconds))

    private fun request(vararg params: Pair<String, String>) = AuthRequest("main", params.toMap())

    @Test
    fun `a live token confirms the address`() =
        runTest {
            val subject = method().authenticate(request(MagicLinkAuthMethod.TOKEN_PARAM to token))

            assertEquals("owner@example.com", subject?.email)
            assertEquals("owner@example.com", subject?.externalId)
        }

    @Test
    fun `an expired token does not let anyone in`() =
        runTest {
            // Thirty seconds is what the sender allows for the redirect: longer means the link
            // was forwarded or opened later.
            assertNull(method(epochSeconds = 1785000031).authenticate(request(MagicLinkAuthMethod.TOKEN_PARAM to token)))
        }

    @Test
    fun `a token on a different secret does not let anyone in`() =
        runTest {
            val alien = MagicLinkAuthMethod("not the secret", clockAt(1785000010))

            assertNull(alien.authenticate(request(MagicLinkAuthMethod.TOKEN_PARAM to token)))
        }

    @Test
    fun `without a token the method stays silent`() =
        runTest {
            assertNull(method().authenticate(request()))
            assertNull(method().authenticate(request(MagicLinkAuthMethod.TOKEN_PARAM to "")))
        }

    @Test
    fun `it confirms ownership of the address`() =
        runTest {
            // The right to link a sign-in to an existing person depends on this. The flag belongs
            // to the identity rather than to the method: Google, for one, does not always confirm
            // an address, and its answer is what decides.
            val subject = method().authenticate(request(MagicLinkAuthMethod.TOKEN_PARAM to token))

            assertTrue(subject!!.emailVerified)
        }
}
