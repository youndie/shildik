package ru.workinprogress.shildik.auth.password

import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.core.feature.auth.InteractiveAuthMethod
import ru.workinprogress.shildik.core.model.LoginAttempt
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.UserRepository
import ru.workinprogress.shildik.crypto.Passwords
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Signing in with a login and a password — for an internal installation, where an external
 * provider is out of place.
 *
 * The login is an email **or** a person's identifier. There is no separate "username" field in the
 * model and no reason to add one: inside an internal installation the identifier is chosen by an
 * administrator, it is short and unique by definition. Email stays because that is what people
 * sign in with elsewhere.
 *
 * **The answer is always the same.** No such person, no password set, wrong password, attempts
 * exhausted — all of it is `null` on the way out. A difference in answers is itself a hint to
 * whoever is guessing: it tells them which addresses exist.
 */
class PasswordAuthMethod(
    private val tenants: TenantRepository,
    private val users: UserRepository,
    private val credentials: CredentialRepository,
    private val attempts: LoginAttemptRepository,
    private val clock: Clock = Clock.System,
    private val maxFailures: Int = MAX_FAILURES,
    private val lockFor: Duration = LOCK_FOR,
) : InteractiveAuthMethod {
    override val id = ID

    override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject? {
        val login = request[LOGIN_PARAM]?.trim()?.lowercase().orEmpty()
        val password = request[PASSWORD_PARAM].orEmpty()
        if (login.isEmpty() || password.isEmpty()) return null

        val tenant = tenants.byRealm(request.realm) ?: return null
        val now = clock.now()

        // The lockout is checked **before** the password: otherwise guessing carries on and
        // merely ends in a refusal, and the counter becomes decoration.
        val attempt = attempts.find(tenant.id, login)
        if (attempt != null && attempt.locked(now)) return null

        // Email first, identifier second: email is what people sign in with more often, so the
        // common case costs no extra query.
        val user = users.findByEmail(tenant.id, login) ?: users.find(tenant.id, login)
        val stored = user?.let { credentials.find(tenant.id, it.id) }

        // The password is verified even when there is no such person, against a deliberately
        // useless record. Otherwise a non-existent address answers instantly and an existing one
        // after hundreds of milliseconds of PBKDF2 — and that difference is how addresses are
        // enumerated.
        val matched = Passwords.verify(password, stored ?: ABSENT_HASH)

        if (!matched || user == null || !user.enabled) {
            recordFailure(attempt, tenant.id, login, now)
            return null
        }

        attempts.reset(tenant.id, login)
        // `emailVerified` stays `false`: the password was set by an administrator rather than by
        // the mailbox, and this method has no right to link anyone else by that address.
        return AuthenticatedSubject(externalId = user.id, email = user.email, name = user.name)
    }

    private suspend fun recordFailure(
        previous: LoginAttempt?,
        tenantId: ru.workinprogress.shildik.core.model.TenantId,
        login: String,
        now: kotlin.time.Instant,
    ) {
        val failures = (previous?.failures ?: 0) + 1
        attempts.save(
            LoginAttempt(
                tenantId = tenantId,
                login = login,
                failures = failures,
                lockedUntil = if (failures >= maxFailures) now + lockFor else previous?.lockedUntil,
            ),
        )
    }

    companion object {
        const val ID = "password"
        const val LOGIN_PARAM = "login"
        const val PASSWORD_PARAM = "password"

        /** Five attempts — as many as a person spends on typos, and no more. */
        const val MAX_FAILURES = 5

        /**
         * Fifteen minutes: long enough for guessing to stop being worthwhile, short enough not to
         * turn the lockout into a denial of service against the person themselves.
         */
        val LOCK_FOR = 15.minutes

        /**
         * A deliberately useless record, so the comparison runs even when there is nothing to
         * compare against. The same iteration count as the real ones: the difference in timing is
         * precisely what is being hidden.
         */
        private val ABSENT_HASH =
            "pbkdf2-sha512$${Passwords.DEFAULT_ITERATIONS}$" +
                "AAAAAAAAAAAAAAAAAAAAAA$" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
