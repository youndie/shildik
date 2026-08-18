package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Passwords take the **slow** path, unlike client secrets.
 *
 * [Secrets] hashes with SHA-256 and explains why: those secrets are generated, so there is
 * nothing to guess at. A password is chosen by a human, which is precisely the caveat [Secrets]
 * names — so here PBKDF2 with an iteration count that makes guessing expensive.
 *
 * Argon2id would be better, but cryptography-kotlin does not have it, and dragging a JVM-only
 * implementation into a multiplatform server costs more than it gives.
 *
 * **The parameters live in the record, not in the code.** Otherwise raising the iteration count a
 * year from now would mean migrating every record at once — and the same applies to reading a
 * different format should one appear.
 */
object Passwords {
    /**
     * What OWASP recommends for PBKDF2-HMAC-SHA512 (2023).
     *
     * The number is stored in the record, so it can be changed at any time: existing passwords go
     * on being verified with the value they were written with, new ones get the new value.
     */
    const val DEFAULT_ITERATIONS = 210_000

    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 64
    private const val ALGORITHM = "pbkdf2-sha512"

    /**
     * A self-describing hash: `pbkdf2-sha512$iterations$salt$hash`.
     *
     * One column instead of four, because there is no reason to read these fields separately and
     * every reason to keep them from drifting apart when written.
     */
    suspend fun hash(
        password: String,
        iterations: Int = DEFAULT_ITERATIONS,
    ): String {
        require(password.isNotEmpty()) { "password must not be empty" }

        val salt = CryptographyRandom.nextBytes(SALT_BYTES)
        val hash = derive(password, salt, iterations)
        return listOf(ALGORITHM, iterations.toString(), salt.encodeBase64Url(), hash.encodeBase64Url())
            .joinToString(SEPARATOR)
    }

    /**
     * Verification. A record that fails to parse yields `false` rather than an exception: to
     * whoever is presenting the password, a corrupted row must look exactly like a wrong password.
     */
    suspend fun verify(
        password: String,
        stored: String,
    ): Boolean {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != ALGORITHM) return false

        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { parts[2].decodeBase64Url() }.getOrNull() ?: return false

        val actual = derive(password, salt, iterations).encodeBase64Url()
        return Secrets.matches(parts[3], actual)
    }

    private suspend fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray =
        CryptographyProvider.Default
            .get(PBKDF2)
            .secretDerivation(SHA512, iterations, HASH_BYTES.bytes, salt)
            .deriveSecretToByteArray(password.encodeToByteArray())

    private const val SEPARATOR = "$"
}
