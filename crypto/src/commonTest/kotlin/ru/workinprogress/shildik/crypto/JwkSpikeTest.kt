package ru.workinprogress.shildik.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

class JwkSpikeTest {
    @Test
    fun `the public key reads back from a JWK and verifies a signature`() =
        runTest {
            val key = SigningKey.generate("k1")
            val jwk = key.publicJwk()
            val data = "payload".encodeToByteArray()
            val signature = key.sign(data)

            val decoded =
                CryptographyProvider.Default
                    .get(RSA.PKCS1)
                    .publicKeyDecoder(SHA256)
                    .decodeFromByteArray(
                        RSA.PublicKey.Format.JWK,
                        Json.encodeToString(JsonObject.serializer(), jwk).encodeToByteArray(),
                    )

            assertTrue(decoded.signatureVerifier().tryVerifySignature(data, signature))
        }
}
