package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * PKCE — the only thing protecting a public client.
 *
 * A browser application has no secret and cannot have one. Without `code_verifier`, an
 * intercepted authorization code could be exchanged by anyone; with it, only by whoever started
 * the sign-in.
 *
 * **`S256` only.** The spec also allows `plain`, which protects nothing at all: the challenge
 * equals the verifier, so intercepting one hands you the other. Accepting `plain` restores
 * exactly the hole PKCE was invented to close.
 */
object Pkce {
    const val METHOD_S256 = "S256"

    private val sha256 = CryptographyProvider.Default.get(SHA256).hasher()

    /** Verify that `S256(verifier) == challenge`, comparing in constant time. */
    suspend fun matches(
        challenge: String,
        verifier: String,
    ): Boolean {
        if (challenge.isBlank() || verifier.isBlank()) return false
        // Lengths from RFC 7636: below 43 characters a verifier does not carry the entropy the
        // spec assumes.
        if (verifier.length !in 43..128) return false
        return Secrets.matches(challenge, sha256.hash(verifier.encodeToByteArray()).encodeBase64Url())
    }
}
