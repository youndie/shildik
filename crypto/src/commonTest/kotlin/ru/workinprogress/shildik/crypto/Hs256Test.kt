package ru.workinprogress.shildik.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Hs256Test {
    // The token was produced by a **different** implementation (python hmac), not by this one:
    // checking our own signature with our own code only proves we agree with ourselves.
    private val secret = "handoff-test-secret"
    private val token =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9" +
            ".eyJlbWFpbCI6Im93bmVyQGV4YW1wbGUuY29tIiwiaWF0IjoxNzg1MDAwMDAwLCJleHAiOjE3ODUwMDAwMzB9" +
            ".gdL3YGBunVvm4TFSV8ZCpQVZBwAKfR1ycnTovahI82Y"

    @Test
    fun `a signature from another implementation verifies`() =
        runTest {
            val claims = Hs256.verify(token, secret)

            assertEquals("owner@example.com", claims?.get("email")?.jsonPrimitive?.content)
        }

    @Test
    fun `a different secret does not verify`() =
        runTest {
            assertNull(Hs256.verify(token, "not the secret"))
        }

    @Test
    fun `a tampered claim breaks the signature`() =
        runTest {
            val parts = token.split('.')
            val forged =
                """{"email":"victim@example.com","iat":1785000000,"exp":1785000030}"""
                    .encodeToByteArray()
                    .encodeBase64Url()

            assertNull(Hs256.verify("${parts[0]}.$forged.${parts[2]}", secret))
        }

    @Test
    fun `something that is not a token is not a token`() =
        runTest {
            assertNull(Hs256.verify("not a jwt at all", secret))
        }
}
