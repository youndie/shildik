package ru.workinprogress.shildik.core.feature.admin

import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.TransactionManager
import ru.workinprogress.shildik.core.port.UserRepository
import ru.workinprogress.shildik.core.usecase.UseCase
import ru.workinprogress.shildik.core.usecase.suspendRunCatching
import ru.workinprogress.shildik.crypto.Passwords

/**
 * Importing a person from the previous provider.
 *
 * The identifier arrives **from outside** and is stored verbatim — that is the whole point of the
 * operation (feature-user-import §4). Imported people get no identifiers of ours: a relying service
 * recognises a person by `sub`, and a substitution would mean the owner signs in to an empty
 * account.
 *
 * Idempotence here is not "a second run does not fail" but "a second run changes nothing": an
 * import gets run twice if only because somebody interrupts the first one halfway, and the second
 * has to be safe.
 */
class ImportUserUseCase(
    private val tenants: TenantRepository,
    private val users: UserRepository,
    private val transactions: TransactionManager,
) : UseCase<ImportUserUseCase.Params, ImportedUser> {
    override suspend fun invoke(params: Params): Result<ImportedUser> =
        suspendRunCatching {
            transactions.withTransaction {
                val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
                val existing = users.find(tenant.id, params.id)

                val user =
                    User(
                        tenantId = tenant.id,
                        id = params.id,
                        email = params.email,
                        name = params.name,
                        emailVerified = params.emailVerified,
                        enabled = params.enabled,
                        identities = params.identities,
                    )

                // Already imported and unchanged — left alone entirely: `upsert` would write the
                // same thing, but the report would lie that the import did something.
                if (existing == user) return@withTransaction ImportedUser(user, changed = false)

                users.upsert(user)
                ImportedUser(user, changed = true)
            }
        }

    class Params(
        val realm: String,
        val id: String,
        val email: String?,
        val name: String?,
        val emailVerified: Boolean,
        val enabled: Boolean,
        val identities: Set<ExternalIdentity>,
    )
}

data class ImportedUser(
    val user: User,
    /** `false` means this user was already exactly like this; the import report relies on it. */
    val changed: Boolean,
)

class ListUsersUseCase(
    private val tenants: TenantRepository,
    private val users: UserRepository,
) : UseCase<String, List<User>> {
    override suspend fun invoke(params: String): Result<List<User>> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params) ?: throw NotFound("tenant '$params'")
            users.list(tenant.id)
        }
}

/**
 * Setting a person's password — an administrator's operation and the **only** way to get one.
 *
 * There is deliberately no self-service: the internal contour has a handful of people, and "forgot
 * my password" is a separate flow with email, that is, one more subsystem for the sake of three
 * accounts (research-internal-login §2).
 */
class SetPasswordUseCase(
    private val tenants: TenantRepository,
    private val users: UserRepository,
    private val credentials: CredentialRepository,
) : UseCase<SetPasswordUseCase.Params, Unit> {
    override suspend fun invoke(params: Params): Result<Unit> =
        suspendRunCatching {
            require(params.password.length >= MIN_LENGTH) {
                "the password is shorter than $MIN_LENGTH characters"
            }

            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            // A password is set for a person who already exists. Creating them along the way is
            // not allowed: a typo in the identifier would create an account instead of an error.
            val user = users.find(tenant.id, params.userId) ?: throw NotFound("user '${params.userId}'")

            credentials.put(tenant.id, params.userId, Passwords.hash(params.password))

            // A password is a sign-in method, so it appears in the person's list of identities.
            // Without that, signing in with it would run into the identity lookup: the method
            // exists, but no link to the person does, and in a closed tenant that is a refusal.
            val identity = ExternalIdentity(PASSWORD_METHOD, user.id)
            if (identity !in user.identities) {
                users.upsert(user.copy(identities = user.identities + identity))
            }
        }

    class Params(
        val realm: String,
        val userId: String,
        val password: String,
    )

    companion object {
        /**
         * The identifier of the password sign-in method. Duplicated here deliberately: `:core` does
         * not depend on the method modules — they depend on it.
         */
        const val PASSWORD_METHOD = "password"

        /**
         * Below twelve characters PBKDF2 no longer saves you: guessing goes by dictionary, not by
         * alphabet. There is no upper bound — it would be meaningless and would only get in the way
         * of password managers.
         */
        const val MIN_LENGTH = 12
    }
}
