package ru.workinprogress.shildik.core.feature.admin

import ru.workinprogress.shildik.core.feature.keys.ActiveSigningKey
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.KeyState
import ru.workinprogress.shildik.core.model.SigningKeyRecord
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.KeyRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.TransactionManager
import ru.workinprogress.shildik.core.usecase.UseCase
import ru.workinprogress.shildik.core.usecase.suspendRunCatching
import ru.workinprogress.shildik.crypto.MasterKeyCipher
import ru.workinprogress.shildik.crypto.Secrets
import ru.workinprogress.shildik.crypto.SigningKey
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class AlreadyExists(
    what: String,
) : Exception("Already exists: $what")

class NotFound(
    what: String,
) : Exception("Not found: $what")

class CreateTenantUseCase(
    private val tenants: TenantRepository,
) : UseCase<CreateTenantUseCase.Params, Tenant> {
    override suspend fun invoke(params: Params): Result<Tenant> =
        suspendRunCatching {
            if (tenants.byRealm(params.realm) != null) throw AlreadyExists("tenant '${params.realm}'")
            tenants.create(Tenant(TenantId(params.realm), params.realm, params.registrationOpen))
        }

    class Params(
        val realm: String,
        val registrationOpen: Boolean = true,
    )

    /** A short form for tests and bootstrap: an open tenant, the earlier behaviour. */
    suspend operator fun invoke(realm: String): Result<Tenant> = invoke(Params(realm))
}

/** A created client together with its secret — the **only** moment the secret is visible. */
data class CreatedClient(
    val clientId: String,
    /** `null` means the client is public: it has no secret, so there is nothing to show. */
    val secret: String?,
    val roles: Set<String>,
)

class CreateClientUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val transactions: TransactionManager,
) : UseCase<CreateClientUseCase.Params, CreatedClient> {
    override suspend fun invoke(params: Params): Result<CreatedClient> =
        suspendRunCatching {
            transactions.withTransaction {
                val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
                if (clients.find(tenant.id, params.clientId) != null) {
                    // `create` must not quietly turn into `update`: otherwise a repeated call
                    // would wipe the roles of an existing client (feature-client-admin §5).
                    throw AlreadyExists("client '${params.clientId}'")
                }

                // A public client without return addresses cannot be used at all: there is
                // nowhere to return the code to. Such a client is not "not configured yet" but a
                // mistake, and it is better said at creation than at a user's first sign-in.
                require(!params.public || params.redirectUris.isNotEmpty()) {
                    "a public client needs at least one redirect_uri: sign-in is impossible without one"
                }

                // A public client has no secret at all (api/protocol-oidc-browser.md §3).
                // Generating one and not showing it would mean creating a secret known only to the
                // database: useless and real at the same time.
                val secret = if (params.public) null else Secrets.generate()

                clients.upsert(
                    Client(
                        tenantId = tenant.id,
                        clientId = params.clientId,
                        secretHash = secret?.let { Secrets.hash(it) }.orEmpty(),
                        roles = params.roles,
                        public = params.public,
                        redirectUris = params.redirectUris,
                        audiences = params.audiences,
                    ),
                )
                CreatedClient(params.clientId, secret, params.roles)
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
        val roles: Set<String>,
        val public: Boolean = false,
        val redirectUris: Set<String> = emptySet(),
        /** Resources this client may hold a token for (RFC 8707). Empty means no `aud` at all. */
        val audiences: Set<String> = emptySet(),
    )
}

class RotateClientSecretUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val transactions: TransactionManager,
) : UseCase<RotateClientSecretUseCase.Params, CreatedClient> {
    override suspend fun invoke(params: Params): Result<CreatedClient> =
        suspendRunCatching {
            transactions.withTransaction {
                val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
                val existing = clients.find(tenant.id, params.clientId) ?: throw NotFound("client '${params.clientId}'")

                val secret = Secrets.generate()
                clients.upsert(existing.copy(secretHash = Secrets.hash(secret)))
                CreatedClient(existing.clientId, secret, existing.roles)
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
    )
}

/**
 * Accept a **given** secret instead of a generated one.
 *
 * It exists precisely for a migration from another provider: while a client's secret in shildik
 * differs from the one the service already holds, switching over requires redistributing secrets
 * across five services and robs the rollback of meaning — the old secrets no longer fit the old
 * provider either. Make them match and the switch comes down to a single ingress, and the rollback
 * to putting it back.
 *
 * For the same reason the secret is **not checked for "quality"** here: it comes from the provider
 * that issued it, and demanding our format of it is pointless. The only checks are that it is
 * neither empty nor absurdly short — a typo in a migration script must not pass silently.
 */
class SetClientSecretUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val transactions: TransactionManager,
) : UseCase<SetClientSecretUseCase.Params, Client> {
    override suspend fun invoke(params: Params): Result<Client> =
        suspendRunCatching {
            require(params.secret.length >= MIN_SECRET_LENGTH) {
                "the secret is shorter than $MIN_SECRET_LENGTH characters: that looks like a mistake"
            }

            transactions.withTransaction {
                val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
                val existing = clients.find(tenant.id, params.clientId) ?: throw NotFound("client '${params.clientId}'")

                val updated = existing.copy(secretHash = Secrets.hash(params.secret))
                clients.upsert(updated)
                updated
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
        val secret: String,
    )

    companion object {
        /** No provider issues a secret shorter than this — so this is a typo. */
        const val MIN_SECRET_LENGTH = 16
    }
}

class SetClientRolesUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val transactions: TransactionManager,
) : UseCase<SetClientRolesUseCase.Params, Client> {
    override suspend fun invoke(params: Params): Result<Client> =
        suspendRunCatching {
            transactions.withTransaction {
                val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
                val existing = clients.find(tenant.id, params.clientId) ?: throw NotFound("client '${params.clientId}'")
                val updated = existing.copy(roles = params.roles)
                clients.upsert(updated)
                updated
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
        val roles: Set<String>,
        val public: Boolean = false,
        val redirectUris: Set<String> = emptySet(),
    )
}

/**
 * Setting which resources a client may hold a token for.
 *
 * Separate from creation because the clients that need it most already exist: a service that has
 * been issuing tokens for a year is exactly the one whose tokens nobody could check the audience
 * of. Recreating it to add one would mean a new secret and a service down until somebody notices.
 */
class SetClientAudiencesUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val transactions: TransactionManager,
) : UseCase<SetClientAudiencesUseCase.Params, Client> {
    override suspend fun invoke(params: Params): Result<Client> =
        suspendRunCatching {
            transactions.withTransaction {
                val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
                val existing = clients.find(tenant.id, params.clientId) ?: throw NotFound("client '${params.clientId}'")
                val updated = existing.copy(audiences = params.audiences)
                clients.upsert(updated)
                updated
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
        val audiences: Set<String>,
    )
}

class DeleteClientUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
) : UseCase<DeleteClientUseCase.Params, Unit> {
    override suspend fun invoke(params: Params): Result<Unit> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            clients.find(tenant.id, params.clientId) ?: throw NotFound("client '${params.clientId}'")
            clients.delete(tenant.id, params.clientId)
        }

    class Params(
        val realm: String,
        val clientId: String,
    )
}

class ListClientsUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
) : UseCase<String, List<Client>> {
    override suspend fun invoke(params: String): Result<List<Client>> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params) ?: throw NotFound("tenant '$params'")
            clients.list(tenant.id)
        }
}

/**
 * Rotating the signing key.
 *
 * The previous key is **not deleted** but moves to `RETIRING` and keeps being served in JWKS:
 * clients cache keys for a day, and a key that has vanished breaks verification silently
 * (feature-signing-keys §3).
 */
class RotateKeyUseCase(
    private val tenants: TenantRepository,
    private val keys: KeyRepository,
    private val activeKey: ActiveSigningKey,
    private val transactions: TransactionManager,
    private val cipher: MasterKeyCipher,
    private val clock: Clock = Clock.System,
) : UseCase<String, String> {
    override suspend fun invoke(params: String): Result<String> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params) ?: throw NotFound("tenant '$params'")

            transactions.withTransaction {
                keys.active(tenant.id)?.let { keys.updateState(tenant.id, it.kid, KeyState.RETIRING) }

                val kid = "k-" + clock.now().toEpochMilliseconds().toString(36)
                val generated = SigningKey.generate(kid)
                keys.save(
                    SigningKeyRecord(
                        tenantId = tenant.id,
                        kid = kid,
                        privateKeyDer = cipher.encrypt(generated.encodePrivate()),
                        state = KeyState.ACTIVE,
                        createdAt = clock.now(),
                        retiringSince = null,
                    ),
                )
                activeKey.invalidate(tenant.id)
                kid
            }
        }
}

class RetireKeyUseCase(
    private val tenants: TenantRepository,
    private val keys: KeyRepository,
    private val clock: Clock = Clock.System,
) : UseCase<RetireKeyUseCase.Params, Unit> {
    override suspend fun invoke(params: Params): Result<Unit> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val record =
                keys.all(tenant.id).firstOrNull { it.kid == params.kid } ?: throw NotFound("key '${params.kid}'")

            val since = record.retiringSince
            if (record.state != KeyState.RETIRING || since == null) {
                throw IllegalStateException("Rotate first: key '${params.kid}' is not retiring yet")
            }
            if (clock.now() - since < JWKS_CACHE_WINDOW) {
                // The rule exists for somebody else's cache, and at this moment a person is
                // usually certain that they, at least, know what they are doing
                // (feature-signing-keys §5).
                throw IllegalStateException(
                    "Key '${params.kid}' cannot be retired sooner than ${JWKS_CACHE_WINDOW.inWholeHours} h after rotation: " +
                        "clients cache JWKS for a day and would get a 401 on everything",
                )
            }
            keys.updateState(tenant.id, params.kid, KeyState.RETIRED)
        }

    class Params(
        val realm: String,
        val kid: String,
    )

    companion object {
        /** A measurement, not caution: the clients' `JwkProviderBuilder` caches for 24 hours. */
        val JWKS_CACHE_WINDOW = 24.hours
    }
}

/**
 * Re-encrypt the private keys with the current master key (research §R13).
 *
 * The second step of a master-key change: after a rollout with the new key first in the list, the
 * records are still encrypted with the old one, and it must stay in the configuration. This
 * operation lifts that requirement — afterwards the old key can be dropped.
 *
 * Idempotent: records already encrypted with the current key are left alone. Which means it can be
 * run repeatedly and put into a script.
 */
class ReencryptKeysUseCase(
    private val tenants: TenantRepository,
    private val keys: KeyRepository,
    private val cipher: MasterKeyCipher,
    private val transactions: TransactionManager,
) : UseCase<Unit, ReencryptReport> {
    override suspend fun invoke(params: Unit): Result<ReencryptReport> =
        suspendRunCatching {
            var reencrypted = 0
            var untouched = 0

            transactions.withTransaction {
                for (tenant in tenants.list()) {
                    for (record in keys.all(tenant.id)) {
                        if (cipher.isCurrent(record.privateKeyDer)) {
                            untouched++
                            continue
                        }
                        val plain = cipher.decrypt(record.privateKeyDer)
                        keys.save(record.copy(privateKeyDer = cipher.encrypt(plain)))
                        reencrypted++
                    }
                }
            }

            ReencryptReport(reencrypted = reencrypted, untouched = untouched)
        }
}

data class ReencryptReport(
    val reencrypted: Int,
    val untouched: Int,
)

class ListKeysUseCase(
    private val tenants: TenantRepository,
    private val keys: KeyRepository,
) : UseCase<String, List<SigningKeyRecord>> {
    override suspend fun invoke(params: String): Result<List<SigningKeyRecord>> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params) ?: throw NotFound("tenant '$params'")
            keys.all(tenant.id)
        }
}

class ListTenantsUseCase(
    private val tenants: TenantRepository,
) : UseCase<Unit, List<Tenant>> {
    override suspend fun invoke(params: Unit): Result<List<Tenant>> = suspendRunCatching { tenants.list() }
}
