package ru.workinprogress.oidc

import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.crypto.SigningKey
import ru.workinprogress.shildik.crypto.VerificationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MultiSourceKeysTest {
    /** A source that knows its own `kid`s and counts how often it was asked. */
    private class Source(
        private vararg val known: String,
    ) : KeySource {
        var calls = 0
        var offline = false

        override suspend fun key(kid: String): VerificationKey? {
            calls++
            if (offline || kid !in known) return null
            return VerificationKey.fromJwk(SigningKey.generate(kid).publicJwk())
        }
    }

    @Test
    fun `a key is found in the second source`() =
        runTest {
            val keys = MultiSourceKeys(listOf(Source("legacy-1"), Source("newcomer-1")))

            assertNotNull(keys.key("newcomer-1"))
        }

    @Test
    fun `the search happens once per kid and not per request`() =
        runTest {
            val first = Source("legacy-1")
            val second = Source("newcomer-1")
            val keys = MultiSourceKeys(listOf(first, second))

            repeat(20) { keys.key("newcomer-1") }

            // This is the point of the class: otherwise every token from the second provider
            // would cost a JWKS fetch from the first — a network request per user request.
            assertEquals(1, first.calls, "the first source is asked only until the kid is known")
            assertEquals(20, second.calls)
        }

    @Test
    fun `a route is reconsidered when its source stops serving the key`() =
        runTest {
            val first = Source("shared")
            val second = Source("shared")
            val keys = MultiSourceKeys(listOf(first, second))

            keys.key("shared")
            first.offline = true

            // The key left the first source, so it is taken from the second rather than failing
            // on the remembered route: that is how a rotation is survived.
            assertNotNull(keys.key("shared"))
        }

    @Test
    fun `an unknown key is rejected`() =
        runTest {
            assertNull(MultiSourceKeys(listOf(Source("a"), Source("b"))).key("foreign"))
        }
}
