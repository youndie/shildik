package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Verifying a **foreign** token against a shared secret.
 *
 * The single exception to [Jws]'s rule that no foreign formats are accepted here. A trusted
 * neighbour hands over a JWT signed with HMAC-SHA256 using a secret both sides know. That is not
 * a signature in the OIDC sense but an assertion by a party we already trust — typically "this
 * email address has been confirmed".
 *
 * Exactly that shape is parsed and nothing more: `HS256`, claims without `iss` or `aud`. Keeping
 * it narrow removes the temptation to accept an arbitrary JWT here.
 */
object Hs256 {
    private val hmac = CryptographyProvider.Default.get(HMAC)

    /**
     * @return the claims when the signature matches, `null` otherwise
     */
    suspend fun verify(
        token: String,
        secret: String,
    ): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3) return null

        return runCatching {
            val key = hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
            val signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray()

            // Verified through the provider rather than by comparing arrays: a byte comparison
            // that stops at the first difference leaks time and turns into signature guessing.
            val valid =
                key
                    .signatureVerifier()
                    .tryVerifySignature(signingInput, parts[2].decodeBase64Url())

            if (!valid) return null

            Json.parseToJsonElement(parts[1].decodeBase64Url().decodeToString()) as JsonObject
        }.getOrNull()
    }
}
