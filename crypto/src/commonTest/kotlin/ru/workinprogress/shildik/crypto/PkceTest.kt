package ru.workinprogress.shildik.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PKCE is the only protection a public client has, so what is tested is not "does it work" but
 * "can it be bypassed".
 */
class PkceTest {
    // The pair from RFC 7636, appendix B: an outside vector is worth more than one computed here.
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test
    fun `the right verifier matches`() = runTest { assertTrue(Pkce.matches(challenge, verifier)) }

    @Test
    fun `a different verifier does not match`() =
        runTest {
            assertFalse(Pkce.matches(challenge, "x".repeat(43)))
        }

    @Test
    fun `the challenge itself is not accepted as the verifier`() =
        runTest {
            // This is the `plain` substitution: were it accepted, an intercepted code plus the
            // challenge from the request would be enough to exchange it.
            assertFalse(Pkce.matches(challenge, challenge))
        }

    @Test
    fun `too short a verifier is rejected`() =
        runTest {
            assertFalse(Pkce.matches(challenge, "short"))
        }

    @Test
    fun `blank values are rejected`() =
        runTest {
            assertFalse(Pkce.matches("", verifier))
            assertFalse(Pkce.matches(challenge, ""))
        }
}
