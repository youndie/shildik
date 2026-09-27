package io.github.youndie.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A compact JWS signed with HMAC-SHA256 over a secret both sides know.
 *
 * The single exception to [Jws]'s rule that no foreign formats are accepted here. A trusted
 * neighbour hands over a JWT signed with a shared secret. That is not a signature in the OIDC
 * sense but an assertion by a party we already trust — typically "this email address has been
 * confirmed".
 *
 * Exactly that shape and nothing more: `HS256`, claims as they are. Keeping it narrow removes the
 * temptation to accept an arbitrary JWT here. Checking `exp`, `iss`, `aud` or anything else in the
 * claims is the caller's job: this object answers only "was it signed with this secret".
 *
 * Both halves live here — [sign] for the party that issues such a token and [verify] for the one
 * that accepts it — so the format has one owner and cannot drift between them.
 */
public object Hs256 {
    private val hmac = CryptographyProvider.Default.get(HMAC)

    // Built here and never taken from the caller: letting `alg` be a parameter is the road to
    // `alg: none`, the same reasoning as in [Jws].
    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    /**
     * Signs [claims] as a compact JWS: base64url header, base64url claims (compact JSON, keys in
     * the order given), base64url HMAC-SHA256 signature.
     */
    public suspend fun sign(
        claims: JsonObject,
        secret: String,
    ): String {
        val key = hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())

        val signingInput =
            HEADER.encodeToByteArray().encodeBase64Url() + "." +
                Json.encodeToString(JsonObject.serializer(), claims).encodeToByteArray().encodeBase64Url()

        val signature = key.signatureGenerator().generateSignature(signingInput.encodeToByteArray())

        return signingInput + "." + signature.encodeBase64Url()
    }

    /**
     * @return the claims when the signature matches, `null` otherwise
     */
    public suspend fun verify(
        token: String,
        secret: String,
    ): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3) return null

        @Suppress(
            "ktlint:kapkan:cancellation-swallowed",
            "HMAC считается на месте: провайдер suspend по сигнатуре, но не приостанавливается",
        )
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
