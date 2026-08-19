package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The key cache: how often the provider is asked, and when it stops being asked. */
class JwksSourceTest {
    private val provider = Provider("provider-key", "https://provider.test")

    private fun source(now: () -> kotlin.time.Instant = { Clock.System.now() }) =
        JwksSource(HttpClient(jwksEngine(provider)), jwksUrl = { provider.certs }, now = now)

    @Test
    fun `keys are fetched once for all requests`() =
        runTest {
            val source = source()

            repeat(20) { assertNotNull(source.key(provider.kid)) }

            // This is what the cache is for: otherwise somebody else's availability sits in the
            // hot path — a JWKS fetch per user request.
            assertEquals(1, provider.calls)
        }

    @Test
    fun `after a day the keys are refetched`() =
        runTest {
            var moment = Clock.System.now()
            val source = source { moment }

            assertNotNull(source.key(provider.kid))
            moment += 2.days

            assertNotNull(source.key(provider.kid))
            assertEquals(2, provider.calls)
        }

    @Test
    fun `an unknown kid does not turn into a JWKS fetch per request`() =
        runTest {
            var moment = Clock.System.now()
            val source = source { moment }

            source.key(provider.kid)
            repeat(50) { assertNull(source.key("junk-kid")) }

            // One request — the one that warmed the cache. Fifty junk tokens from outside did not
            // become fifty requests to the provider; `jwks-rsa` has no such protection by default.
            assertEquals(1, provider.calls)

            // A miss cache must still survive a key rotation, so a minute later it asks again.
            moment += 1.minutes
            assertNull(source.key("junk-kid"))
            assertEquals(2, provider.calls)
        }

    @Test
    fun `an unreachable provider is a miss and not an error`() =
        runTest {
            provider.offline = true
            val source = source()

            // A seamless migration rests on exactly this: while the second source is configured
            // but dead, sign-in has to keep working through the first.
            assertNull(source.key(provider.kid))

            provider.offline = false
            assertNotNull(source.key(provider.kid))
        }

    @Test
    fun `a cached key outlives the provider going away`() =
        runTest {
            val source = source()
            assertNotNull(source.key(provider.kid))

            provider.offline = true

            // The provider is down; sign-in with an already known key keeps working for a day.
            assertNotNull(source.key(provider.kid))
        }

    @Test
    fun `refetching happens at most once every ten seconds`() =
        runTest {
            var moment = Clock.System.now()
            val source = source { moment }

            source.key(provider.kid)
            source.key("foreign")
            assertEquals(1, provider.calls, "a second attempt inside the window must not reach the provider")

            moment += 11.seconds
            source.key("foreign")
            assertEquals(2, provider.calls, "the window is over, so ask again")
        }
}
