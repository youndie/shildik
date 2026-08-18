package ru.workinprogress.shildik.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwsTest {
    private val claims = JsonObject(mapOf("sub" to JsonPrimitive("orders"), "azp" to JsonPrimitive("orders")))

    @Test
    fun `a token has three parts and carries kid in the header`() =
        runTest {
            val key = SigningKey.generate("k1")

            val token = Jws.sign(key, claims)
            val parts = token.split(".")

            assertEquals(3, parts.size)
            val header = Json.parseToJsonElement(parts[0].decodeBase64Url().decodeToString()) as JsonObject
            assertEquals("RS256", header["alg"]?.jsonPrimitive?.content)
            assertEquals("k1", header["kid"]?.jsonPrimitive?.content)
        }

    @Test
    fun `the JWK carries kid and what the key is for`() =
        runTest {
            val jwk = SigningKey.generate("k7").publicJwk()

            assertEquals("k7", jwk["kid"]?.jsonPrimitive?.content)
            assertEquals("RS256", jwk["alg"]?.jsonPrimitive?.content)
            assertEquals("sig", jwk["use"]?.jsonPrimitive?.content)
            assertEquals("RSA", jwk["kty"]?.jsonPrimitive?.content)
            assertTrue(jwk.containsKey("n") && jwk.containsKey("e"))
        }

    /** The private half must never end up in what a JWKS endpoint serves. */
    @Test
    fun `the public JWK holds no private parameters`() =
        runTest {
            val jwk = SigningKey.generate("k1").publicJwk()

            for (private in listOf("d", "p", "q", "dp", "dq", "qi")) {
                assertTrue(private !in jwk, "private parameter '$private' leaked into the public JWK")
            }
        }

    @Test
    fun `a key survives being stored and restored`() =
        runTest {
            val original = SigningKey.generate("k1")
            val restored = SigningKey.fromPrivateDer("k1", original.encodePrivate())

            assertEquals(original.publicJwk()["n"], restored.publicJwk()["n"])
        }

    @Test
    fun `base64url has no padding and no unsafe characters`() {
        for (size in 1..32) {
            val encoded = ByteArray(size) { it.toByte() }.encodeBase64Url()

            assertTrue('=' !in encoded, "padding in '$encoded'")
            assertTrue('+' !in encoded && '/' !in encoded, "unsafe character in '$encoded'")
        }
    }

    @Test
    fun `base64url round-trips at every length`() {
        for (size in 0..64) {
            val bytes = ByteArray(size) { (it * 7 - 128).toByte() }

            assertTrue(bytes.contentEquals(bytes.encodeBase64Url().decodeBase64Url()), "length $size")
        }
    }
}
