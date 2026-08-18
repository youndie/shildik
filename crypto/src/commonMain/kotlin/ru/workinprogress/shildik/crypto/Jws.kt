package ru.workinprogress.shildik.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JWS compact serialization — [RFC 7515 §7.1](https://www.rfc-editor.org/rfc/rfc7515#section-7.1).
 *
 * Issuing and parsing. Parsing was first needed to recognise the bearer of a token we issued
 * ourselves, and later to verify tokens issued by someone else: there is no JOSE library for
 * Kotlin/Native, which is why this one exists. The verification key lives separately, in
 * [VerificationKey].
 *
 * Foreign **formats** still do not get in: there is one algorithm and it is hard-wired.
 */
object Jws {
    private val json = Json { encodeDefaults = true }

    /** A parsed token. `kid` from the header is what picks the key before the signature is checked. */
    class Parsed(
        val alg: String?,
        val kid: String?,
        val claims: JsonObject,
        val signingInput: ByteArray,
        val signature: ByteArray,
    )

    /**
     * Parsing without verification. The claims must not be trusted until the signature matches,
     * which is why a separate type comes back rather than the claims themselves.
     */
    fun parse(token: String): Parsed? {
        val parts = token.split('.')
        if (parts.size != 3) return null

        return runCatching {
            val header = Json.parseToJsonElement(parts[0].decodeBase64Url().decodeToString()) as JsonObject
            // The header `alg` is **not** used to select an algorithm: there is one and it is
            // hard-wired. Selecting by it is the straight road to `alg: none`.
            Parsed(
                // `alg` is returned so the caller can **reject** an unexpected algorithm, not so
                // it can pick one. The difference between those two uses is exactly `alg: none`.
                alg = (header["alg"] as? JsonPrimitive)?.content,
                kid = (header["kid"] as? JsonPrimitive)?.content,
                claims = Json.parseToJsonElement(parts[1].decodeBase64Url().decodeToString()) as JsonObject,
                signingInput = (parts[0] + "." + parts[1]).encodeToByteArray(),
                signature = parts[2].decodeBase64Url(),
            )
        }.getOrNull()
    }

    /**
     * `alg` and `typ` are set here and never come from the caller.
     *
     * Not a detail: letting `alg` in as a parameter is the straight road to `alg: none` and to
     * algorithm substitution. There is one algorithm here and it is hard-wired.
     */
    suspend fun sign(
        key: SigningKey,
        claims: JsonObject,
    ): String {
        val header =
            JsonObject(
                mapOf(
                    "alg" to JsonPrimitive("RS256"),
                    "typ" to JsonPrimitive("JWT"),
                    "kid" to JsonPrimitive(key.kid),
                ),
            )

        val signingInput =
            json.encodeToString(JsonObject.serializer(), header).encodeToByteArray().encodeBase64Url() +
                "." +
                json.encodeToString(JsonObject.serializer(), claims).encodeToByteArray().encodeBase64Url()

        val signature = key.sign(signingInput.encodeToByteArray())
        return signingInput + "." + signature.encodeBase64Url()
    }
}
