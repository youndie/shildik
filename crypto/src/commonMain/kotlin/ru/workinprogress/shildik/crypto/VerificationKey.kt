package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The public key of **another** provider, for verifying signatures and nothing else.
 *
 * This deliberately exists. The usual answer — "let clients verify with an existing library" —
 * stops working the moment a client runs on Kotlin/Native: `jwks-rsa` and the JWT plugins built
 * on it are JVM-only. Either verification lives in common code, or services stay on the JVM
 * forever.
 *
 * The scope stays small for the same reason it was tolerable to write at all: parsing one kind of
 * JWK and one signature check. No other kinds of key appear here — see [fromJwk].
 */
class VerificationKey(
    val kid: String?,
    private val publicKey: RSA.PKCS1.PublicKey,
) {
    suspend fun verify(
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = publicKey.signatureVerifier().tryVerifySignature(data, signature)

    companion object {
        /**
         * A key from a single JWK, or `null` when the key is not one we can use.
         *
         * Such keys are skipped silently, and on purpose: a provider's JWKS holds keys for
         * different jobs — encryption, other algorithms — and meeting one is normal rather than
         * broken. The only accepted shape is RSA for signing: an `alg` other than `RS256` is
         * rejected **here**, before any verification, so that a supplied algorithm can never
         * become the choice of algorithm.
         */
        suspend fun fromJwk(jwk: JsonObject): VerificationKey? {
            fun text(name: String) = (jwk[name] as? JsonPrimitive)?.content

            if (text("kty") != "RSA") return null
            text("alg")?.let { if (it != "RS256") return null }
            text("use")?.let { if (it != "sig") return null }

            val decoded =
                runCatching {
                    CryptographyProvider.Default
                        .get(RSA.PKCS1)
                        .publicKeyDecoder(SHA256)
                        .decodeFromByteArray(
                            RSA.PublicKey.Format.JWK,
                            Json.encodeToString(JsonObject.serializer(), jwk).encodeToByteArray(),
                        )
                }.getOrNull() ?: return null

            return VerificationKey(text("kid"), decoded)
        }

        /**
         * Keys from a JWKS document — [RFC 7517 §5](https://www.rfc-editor.org/rfc/rfc7517#section-5).
         *
         * An empty list is a legitimate result: a provider may hold no key of a usable kind, and
         * that is no different from "there is no key with this `kid`".
         */
        suspend fun fromJwks(json: String): List<VerificationKey> {
            val keys =
                runCatching {
                    (Json.parseToJsonElement(json) as JsonObject)["keys"]?.jsonArray
                }.getOrNull() ?: return emptyList()

            return keys.mapNotNull { runCatching { fromJwk(it.jsonObject) }.getOrNull() }
        }
    }
}
