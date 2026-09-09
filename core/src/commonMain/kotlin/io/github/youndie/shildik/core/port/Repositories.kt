package io.github.youndie.shildik.core.port

import io.github.youndie.shildik.core.model.AuthorizationCode
import io.github.youndie.shildik.core.model.Client
import io.github.youndie.shildik.core.model.ExternalIdentity
import io.github.youndie.shildik.core.model.KeyState
import io.github.youndie.shildik.core.model.LoginAttempt
import io.github.youndie.shildik.core.model.PendingAuthorization
import io.github.youndie.shildik.core.model.RefreshToken
import io.github.youndie.shildik.core.model.SigningKeyRecord
import io.github.youndie.shildik.core.model.Tenant
import io.github.youndie.shildik.core.model.TenantId
import io.github.youndie.shildik.core.model.User

/**
 * The storage ports. `:core` knows only these; what is behind them — SQL, memory or something else
 * — is decided by the build (research §R4).
 *
 * The methods are suspend even though a JDBC implementation blocks: the port lives in `commonMain`,
 * and the blocking hides in the adapter behind `Dispatchers.IO`. The reverse order — making the
 * port synchronous "because the ORM is like that" — would drag an engine detail into the domain.
 */

public interface TenantRepository {
    public suspend fun byRealm(realm: String): Tenant?

    public suspend fun byId(id: TenantId): Tenant?

    public suspend fun list(): List<Tenant>

    public suspend fun create(tenant: Tenant): Tenant
}

public interface AuthorizationCodeRepository {
    public suspend fun save(code: AuthorizationCode)

    public suspend fun find(
        tenantId: TenantId,
        codeHash: String,
    ): AuthorizationCode?

    /**
     * Mark it used. Returns `false` if the code was already marked — single use is built on this:
     * two simultaneous exchanges must not yield two tokens.
     */
    public suspend fun markUsed(
        tenantId: TenantId,
        codeHash: String,
    ): Boolean
}

public interface PendingAuthorizationRepository {
    public suspend fun save(pending: PendingAuthorization)

    /** Find and **delete**: a return from an external provider is single-use, like the code. */
    public suspend fun take(
        tenantId: TenantId,
        state: String,
    ): PendingAuthorization?

    /**
     * Find **without deleting**.
     *
     * The form needs this: a typo in a password must not send a person through the whole sign-in
     * again. The request is deleted only after a successful sign-in.
     */
    public suspend fun find(
        tenantId: TenantId,
        state: String,
    ): PendingAuthorization?

    public suspend fun delete(
        tenantId: TenantId,
        state: String,
    )
}

public interface RefreshTokenRepository {
    public suspend fun save(token: RefreshToken)

    public suspend fun find(
        tenantId: TenantId,
        tokenHash: String,
    ): RefreshToken?

    /** Mark it used. `false` means it was already marked, that is, presented twice. */
    public suspend fun markUsed(
        tenantId: TenantId,
        tokenHash: String,
    ): Boolean

    /** Revoke the whole chain: presenting a spent token means a leak. */
    public suspend fun revokeFamily(
        tenantId: TenantId,
        family: String,
    )

    /** Revoke everything issued to this person for this client — on sign-out. */
    public suspend fun revokeForUser(
        tenantId: TenantId,
        clientId: String,
        userId: String,
    )
}

public interface UserRepository {
    public suspend fun find(
        tenantId: TenantId,
        id: String,
    ): User?

    public suspend fun findByIdentity(
        tenantId: TenantId,
        identity: ExternalIdentity,
    ): User?

    /**
     * A lookup by email — for linking a sign-in to a person who already exists.
     *
     * Only a sign-in method that proves ownership of the email may use this
     * (feature-magic-link §2), which is why there is a single call site — in `AuthorizeUseCase`.
     */
    public suspend fun findByEmail(
        tenantId: TenantId,
        email: String,
    ): User?

    public suspend fun list(tenantId: TenantId): List<User>

    public suspend fun upsert(user: User)
}

/**
 * Passwords are their own storage rather than a field on the user.
 *
 * The reason is not normalisation: a user may have no password at all (and most have none), while a
 * hash is the one thing that must never be shown, not even by accident, alongside a profile. A
 * separate table makes that a property of the schema rather than of discipline
 * (research-internal-login §7).
 */
public interface CredentialRepository {
    /** The hash in self-describing form, see `Passwords`. `null` means the person has none. */
    public suspend fun find(
        tenantId: TenantId,
        userId: String,
    ): String?

    public suspend fun put(
        tenantId: TenantId,
        userId: String,
        passwordHash: String,
    )

    public suspend fun delete(
        tenantId: TenantId,
        userId: String,
    )
}

public interface ClientRepository {
    public suspend fun find(
        tenantId: TenantId,
        clientId: String,
    ): Client?

    public suspend fun list(tenantId: TenantId): List<Client>

    public suspend fun upsert(client: Client)

    public suspend fun delete(
        tenantId: TenantId,
        clientId: String,
    )
}

public interface KeyRepository {
    /** The key we sign with now. Exactly one per tenant — or none, if it has not been created. */
    public suspend fun active(tenantId: TenantId): SigningKeyRecord?

    /** Everything that belongs in JWKS: `ACTIVE` and `RETIRING` (feature-signing-keys §2). */
    public suspend fun published(tenantId: TenantId): List<SigningKeyRecord>

    public suspend fun all(tenantId: TenantId): List<SigningKeyRecord>

    public suspend fun save(record: SigningKeyRecord)

    public suspend fun updateState(
        tenantId: TenantId,
        kid: String,
        state: KeyState,
    )
}

/** Storage for failure counters: it cannot be kept in memory — there is more than one pod. */
public interface LoginAttemptRepository {
    public suspend fun find(
        tenantId: TenantId,
        login: String,
    ): LoginAttempt?

    public suspend fun save(attempt: LoginAttempt)

    public suspend fun reset(
        tenantId: TenantId,
        login: String,
    )
}

/**
 * Whether storage is alive is a question of **readiness**, not of the domain.
 *
 * A separate port rather than a query to a repository: readiness asks "is the database reachable",
 * and answering that by selecting tenants would tie the probe to the model — change the model and
 * the meaning of the probe changes. An implementation makes the cheapest query it can.
 */
public fun interface StorageHealth {
    /** `false` rather than an exception: an unreachable database is an expected probe answer. */
    public suspend fun check(): Boolean
}
