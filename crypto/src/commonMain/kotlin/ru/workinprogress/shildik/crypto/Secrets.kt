package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Client secrets: generation, hashing, comparison.
 *
 * **Why SHA-256 and not bcrypt or PBKDF2.** Slow hashes exist to defeat dictionary attacks, which
 * means they exist where a human chose the secret. Here the secret is generated: 32 random bytes
 * from a CSPRNG, with nothing to guess at. Verification, on the other hand, sits on the hot path
 * of issuing a token, and PBKDF2 with a hundred thousand iterations would add tens of
 * milliseconds to every request — paying for strength that is already there.
 *
 * Should human-chosen secrets ever appear, this reasoning stops holding, and they will need their
 * own, deliberately slow path.
 */
object Secrets {
    private const val SECRET_BYTES = 32

    /** A new client secret. Shown once and never recoverable afterwards. */
    fun generate(): String = CryptographyRandom.nextBytes(SECRET_BYTES).encodeBase64Url()

    suspend fun hash(secret: String): String =
        CryptographyProvider.Default
            .get(SHA256)
            .hasher()
            .hash(secret.encodeToByteArray())
            .encodeBase64Url()

    /**
     * Constant-time comparison.
     *
     * Plain `==` on strings leaves the loop at the first mismatch, and the difference in response
     * time tells the caller how many characters they have guessed. Against a CSPRNG secret that
     * is hard to exploit, but the rule is cheap — and exceptions to it have a habit of migrating
     * to places where the secret is weaker.
     */
    fun matches(
        expectedHash: String,
        actualHash: String,
    ): Boolean {
        if (expectedHash.length != actualHash.length) return false
        var diff = 0
        for (i in expectedHash.indices) {
            diff = diff or (expectedHash[i].code xor actualHash[i].code)
        }
        return diff == 0
    }
}
