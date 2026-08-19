package ru.workinprogress.shildik.auth.password

import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.LoginAttempt
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.UserRepository
import ru.workinprogress.shildik.crypto.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PasswordAuthMethodTest {
    private val tenantId = TenantId("internal")
    private val realm = "internal"
    private val login = "ops@example.com"
    private val password = "longenoughpassword"

    private class TestClock(
        var now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ) : Clock {
        override fun now() = now
    }

    private class Tenants(
        private val tenant: Tenant,
    ) : TenantRepository {
        override suspend fun byRealm(realm: String) = tenant.takeIf { it.realm == realm }

        override suspend fun byId(id: TenantId) = tenant.takeIf { it.id == id }

        override suspend fun list() = listOf(tenant)

        override suspend fun create(tenant: Tenant) = tenant
    }

    private class Users(
        private val users: MutableList<User> = mutableListOf(),
    ) : UserRepository {
        override suspend fun find(
            tenantId: TenantId,
            id: String,
        ) = users.firstOrNull { it.id == id }

        override suspend fun findByIdentity(
            tenantId: TenantId,
            identity: ExternalIdentity,
        ) = users.firstOrNull { identity in it.identities }

        override suspend fun findByEmail(
            tenantId: TenantId,
            email: String,
        ) = users.firstOrNull { it.email == email }

        override suspend fun list(tenantId: TenantId) = users.toList()

        override suspend fun upsert(user: User) {
            users.removeAll { it.id == user.id }
            users += user
        }
    }

    private class Credentials(
        private val hashes: MutableMap<String, String> = mutableMapOf(),
    ) : CredentialRepository {
        override suspend fun find(
            tenantId: TenantId,
            userId: String,
        ) = hashes[userId]

        override suspend fun put(
            tenantId: TenantId,
            userId: String,
            passwordHash: String,
        ) {
            hashes[userId] = passwordHash
        }

        override suspend fun delete(
            tenantId: TenantId,
            userId: String,
        ) {
            hashes.remove(userId)
        }
    }

    private class Attempts(
        val stored: MutableMap<String, LoginAttempt> = mutableMapOf(),
    ) : LoginAttemptRepository {
        override suspend fun find(
            tenantId: TenantId,
            login: String,
        ) = stored[login]

        override suspend fun save(attempt: LoginAttempt) {
            stored[attempt.login] = attempt
        }

        override suspend fun reset(
            tenantId: TenantId,
            login: String,
        ) {
            stored.remove(login)
        }
    }

    private class Fixture(
        val method: PasswordAuthMethod,
        val attempts: Attempts,
        val clock: TestClock,
    )

    private suspend fun fixture(enabled: Boolean = true): Fixture {
        val user =
            User(
                tenantId = tenantId,
                id = "operator",
                email = login,
                name = "Operator",
                emailVerified = true,
                enabled = enabled,
                identities = setOf(ExternalIdentity("password", "operator")),
            )
        val credentials = Credentials()
        credentials.put(tenantId, "operator", Passwords.hash(password, iterations = 1_000))
        val attempts = Attempts()
        val clock = TestClock()

        return Fixture(
            PasswordAuthMethod(
                Tenants(Tenant(tenantId, realm)),
                Users(mutableListOf(user)),
                credentials,
                attempts,
                clock,
            ),
            attempts,
            clock,
        )
    }

    private fun request(
        login: String = this.login,
        password: String = this.password,
    ) = AuthRequest(realm, mapOf("login" to login, "password" to password))

    @Test
    fun `the right password lets the person in`() =
        runTest {
            val f = fixture()

            val subject = assertNotNull(f.method.authenticate(request()))

            assertEquals("operator", subject.externalId, "sub must be the person's identifier")
            assertEquals(login, subject.email)
        }

    @Test
    fun `signing in by identifier works as well as by email`() =
        runTest {
            // Inside an internal installation the identifier is set by an administrator: it is
            // short, and typing an email to reach monitoring is pointless.
            val subject = assertNotNull(fixture().method.authenticate(request(login = "operator")))

            assertEquals("operator", subject.externalId)
        }

    @Test
    fun `a wrong password does not let anyone in`() =
        runTest {
            assertNull(fixture().method.authenticate(request(password = "nope")))
        }

    @Test
    fun `a non-existent login answers exactly the same`() =
        runTest {
            // A difference in answers is a hint to whoever is guessing: it reveals which
            // addresses exist.
            assertNull(fixture().method.authenticate(request(login = "no@example.com")))
        }

    @Test
    fun `a disabled person cannot sign in even with the right password`() =
        runTest {
            assertNull(fixture(enabled = false).method.authenticate(request()))
        }

    @Test
    fun `after five failures sign-in locks`() =
        runTest {
            val f = fixture()
            repeat(5) { f.method.authenticate(request(password = "nope")) }

            // The password is right and it is still refused: otherwise the counter is decoration
            // rather than protection.
            assertNull(f.method.authenticate(request()), "once attempts run out, even the right password is refused")
        }

    @Test
    fun `sign-in returns once the lockout expires`() =
        runTest {
            val f = fixture()
            repeat(5) { f.method.authenticate(request(password = "nope")) }

            f.clock.now += 16.minutes

            assertNotNull(f.method.authenticate(request()), "a lockout must not be forever")
        }

    @Test
    fun `a successful sign-in resets the counter`() =
        runTest {
            val f = fixture()
            repeat(4) { f.method.authenticate(request(password = "nope")) }

            f.method.authenticate(request())

            assertNull(f.attempts.stored[login], "otherwise someone who mistypes accrues a lockout over months")
        }

    @Test
    fun `blank fields do not count as an attempt`() =
        runTest {
            val f = fixture()

            assertNull(f.method.authenticate(request(login = "", password = "")))
            assertNull(f.attempts.stored[""], "an empty form must not spend attempts")
        }
}
