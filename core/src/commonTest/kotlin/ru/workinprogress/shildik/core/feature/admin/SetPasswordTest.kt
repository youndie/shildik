package ru.workinprogress.shildik.core.feature.admin

import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.core.feature.browser.FakeCredentials
import ru.workinprogress.shildik.core.feature.browser.FakeTenants
import ru.workinprogress.shildik.core.feature.browser.FakeUsers
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.crypto.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetPasswordTest {
    private val tenantId = TenantId("internal")
    private val realm = "internal"

    private fun known() =
        User(
            tenantId = tenantId,
            id = "operator",
            email = "ops@example.com",
            name = "Operator",
            emailVerified = true,
            enabled = true,
            identities = emptySet(),
        )

    @Test
    fun `the password is stored as a hash`() =
        runTest {
            val credentials = FakeCredentials()
            val useCase =
                SetPasswordUseCase(
                    FakeTenants(listOf(Tenant(tenantId, realm))),
                    FakeUsers(mutableListOf(known())),
                    credentials,
                )

            useCase(SetPasswordUseCase.Params(realm, "operator", "long-enough-secret")).getOrThrow()

            val stored = credentials.find(tenantId, "operator")
            assertTrue(
                stored != null && stored.startsWith("pbkdf2-sha512\$"),
                "the password has to be stored hashed: $stored",
            )
            assertTrue(Passwords.verify("long-enough-secret", stored))
        }

    @Test
    fun `a short password is not accepted`() =
        runTest {
            val credentials = FakeCredentials()
            val useCase =
                SetPasswordUseCase(
                    FakeTenants(listOf(Tenant(tenantId, realm))),
                    FakeUsers(mutableListOf(known())),
                    credentials,
                )

            val result = useCase(SetPasswordUseCase.Params(realm, "operator", "short"))

            assertTrue(result.isFailure)
            assertNull(credentials.find(tenantId, "operator"), "a failure must leave no record")
        }

    @Test
    fun `a password cannot be set for a person who does not exist`() =
        runTest {
            // Otherwise a typo in the identifier would create an account instead of an error.
            val credentials = FakeCredentials()
            val useCase = SetPasswordUseCase(FakeTenants(listOf(Tenant(tenantId, realm))), FakeUsers(), credentials)

            val result = useCase(SetPasswordUseCase.Params(realm, "no-such-user", "long-enough-secret"))

            assertTrue(result.isFailure)
            assertEquals(null, credentials.find(tenantId, "no-such-user"))
        }
}
