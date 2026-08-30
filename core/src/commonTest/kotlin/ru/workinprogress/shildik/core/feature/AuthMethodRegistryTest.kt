package ru.workinprogress.shildik.core.feature

import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.core.feature.auth.AuthMethod
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A check of the promise from research §R5: adding a sign-in method means implementing an interface
 * and putting it in the registry — no jar inside somebody else's process, no reflection.
 */
class AuthMethodRegistryTest {
    private class FakeMethod(
        override val id: String,
        private val subject: AuthenticatedSubject?,
    ) : AuthMethod {
        override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject? =
            subject.takeIf {
                request["token"] ==
                    "right"
            }
    }

    @Test
    fun `a sign-in method is added by implementing the interface`() =
        runTest {
            val registry =
                AuthMethodRegistry(
                    listOf(FakeMethod("magic-link", AuthenticatedSubject("a@x", "a@x"))),
                )

            val method = registry.find("magic-link")!!

            assertEquals(
                "a@x",
                method.authenticate(AuthRequest("main", mapOf("token" to "right")))?.externalId,
            )
            assertNull(method.authenticate(AuthRequest("main", mapOf("token" to "wrong"))))
        }

    @Test
    fun `an unknown sign-in method is not found`() {
        assertNull(AuthMethodRegistry().find("no-such-method"))
    }

    /**
     * Two methods with one id are one silently standing in for the other. Better not to build at
     * all than to find out on a live sign-in which of them answers.
     */
    @Test
    fun `a duplicate id brings the registry down`() {
        val duplicate =
            assertFailsWith<IllegalArgumentException> {
                AuthMethodRegistry(listOf(FakeMethod("magic-link", null), FakeMethod("magic-link", null)))
            }

        assertEquals(true, duplicate.message?.contains("magic-link"))
    }
}
