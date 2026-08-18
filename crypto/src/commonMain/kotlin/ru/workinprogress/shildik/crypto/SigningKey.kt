package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A signing key pair together with its `kid`.
 *
 * The `kid` is ours to choose and goes into the JWS header: without it a client holding several
 * keys from a JWKS document does not know which one to verify with and has to try them all.
 */
class SigningKey(
    val kid: String,
    private val privateKey: RSA.PKCS1.PrivateKey,
    private val publicKey: RSA.PKCS1.PublicKey,
) {
    suspend fun sign(data: ByteArray): ByteArray = privateKey.signatureGenerator().generateSignature(data)

    /**
     * Verifying the signature of a token **we issued**.
     *
     * The key is known, the algorithm is single, and no foreign formats are parsed. Verifying
     * somebody else's token is a different job, and it lives in [VerificationKey].
     */
    suspend fun verify(
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = publicKey.signatureVerifier().tryVerifySignature(data, signature)

    /** The public half as a JWK — exactly what a JWKS endpoint serves. */
    suspend fun publicJwk(): JsonObject {
        val raw = publicKey.encodeToByteArray(RSA.PublicKey.Format.JWK).decodeToString()
        val parsed = Json.parseToJsonElement(raw) as JsonObject

        // The provider gives the mathematics of the key (kty, n, e). The rest is ours: `kid` so
        // a key can be picked, `alg` and `use` so a client need not guess what it is good for.
        return JsonObject(
            parsed +
                mapOf(
                    "kid" to JsonPrimitive(kid),
                    "alg" to JsonPrimitive("RS256"),
                    "use" to JsonPrimitive("sig"),
                ),
        )
    }

    /** The private half, for storage. It is never served to anyone. */
    suspend fun encodePrivate(): ByteArray = privateKey.encodeToByteArray(RSA.PrivateKey.Format.DER)

    companion object {
        /**
         * RSA-2048 with SHA-256 is what RS256 means. The size is not a parameter: offering the
         * choice here would be offering the choice of a key that is too short.
         */
        suspend fun generate(kid: String): SigningKey {
            val algorithm = CryptographyProvider.Default.get(RSA.PKCS1)
            val keyPair = algorithm.keyPairGenerator(keySize = 2048.bits, digest = SHA256).generateKey()
            return SigningKey(kid, keyPair.privateKey, keyPair.publicKey)
        }

        suspend fun fromPrivateDer(
            kid: String,
            der: ByteArray,
        ): SigningKey {
            val algorithm = CryptographyProvider.Default.get(RSA.PKCS1)
            val privateKey = algorithm.privateKeyDecoder(SHA256).decodeFromByteArray(RSA.PrivateKey.Format.DER, der)
            // The public half is derived from the private one rather than stored beside it: two
            // copies of one fact are an opportunity for them to disagree.
            return SigningKey(kid, privateKey, privateKey.getPublicKey())
        }
    }
}
