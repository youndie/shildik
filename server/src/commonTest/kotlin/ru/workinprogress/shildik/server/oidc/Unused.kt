package ru.workinprogress.shildik.server.oidc

import ru.workinprogress.shildik.core.model.AuthorizationCode
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.KeyState
import ru.workinprogress.shildik.core.model.LoginAttempt
import ru.workinprogress.shildik.core.model.PendingAuthorization
import ru.workinprogress.shildik.core.model.RefreshToken
import ru.workinprogress.shildik.core.model.SigningKeyRecord
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.core.port.AuthorizationCodeRepository
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.KeyRepository
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import ru.workinprogress.shildik.core.port.PendingAuthorizationRepository
import ru.workinprogress.shildik.core.port.RefreshTokenRepository
import ru.workinprogress.shildik.core.port.UserRepository

/**
 * Ports the graph insists on and the test under way does not touch.
 *
 * Every method fails rather than returning an empty answer. An empty answer is a claim — "there is
 * no such client", "this key set is empty" — and a test that walked into one would pass while
 * measuring a fiction. Failing here means a route that reaches past what is being tested says so.
 */
internal object Unused {
    private fun no(port: String): Nothing = error("$port is not part of this test")

    object Codes : AuthorizationCodeRepository {
        override suspend fun save(code: AuthorizationCode) = no("AuthorizationCodeRepository")

        override suspend fun find(
            tenantId: TenantId,
            codeHash: String,
        ): AuthorizationCode? = no("AuthorizationCodeRepository")

        override suspend fun markUsed(
            tenantId: TenantId,
            codeHash: String,
        ): Boolean = no("AuthorizationCodeRepository")
    }

    object Pending : PendingAuthorizationRepository {
        override suspend fun save(pending: PendingAuthorization) = no("PendingAuthorizationRepository")

        override suspend fun take(
            tenantId: TenantId,
            state: String,
        ): PendingAuthorization? = no("PendingAuthorizationRepository")

        override suspend fun find(
            tenantId: TenantId,
            state: String,
        ): PendingAuthorization? = no("PendingAuthorizationRepository")

        override suspend fun delete(
            tenantId: TenantId,
            state: String,
        ) = no("PendingAuthorizationRepository")
    }

    object RefreshTokens : RefreshTokenRepository {
        override suspend fun save(token: RefreshToken) = no("RefreshTokenRepository")

        override suspend fun find(
            tenantId: TenantId,
            tokenHash: String,
        ): RefreshToken? = no("RefreshTokenRepository")

        override suspend fun markUsed(
            tenantId: TenantId,
            tokenHash: String,
        ): Boolean = no("RefreshTokenRepository")

        override suspend fun revokeFamily(
            tenantId: TenantId,
            family: String,
        ) = no("RefreshTokenRepository")

        override suspend fun revokeForUser(
            tenantId: TenantId,
            clientId: String,
            userId: String,
        ) = no("RefreshTokenRepository")
    }

    object Users : UserRepository {
        override suspend fun find(
            tenantId: TenantId,
            id: String,
        ): User? = no("UserRepository")

        override suspend fun findByIdentity(
            tenantId: TenantId,
            identity: ExternalIdentity,
        ): User? = no("UserRepository")

        override suspend fun findByEmail(
            tenantId: TenantId,
            email: String,
        ): User? = no("UserRepository")

        override suspend fun list(tenantId: TenantId): List<User> = no("UserRepository")

        override suspend fun upsert(user: User) = no("UserRepository")
    }

    object Credentials : CredentialRepository {
        override suspend fun find(
            tenantId: TenantId,
            userId: String,
        ): String? = no("CredentialRepository")

        override suspend fun put(
            tenantId: TenantId,
            userId: String,
            passwordHash: String,
        ) = no("CredentialRepository")

        override suspend fun delete(
            tenantId: TenantId,
            userId: String,
        ) = no("CredentialRepository")
    }

    object Clients : ClientRepository {
        override suspend fun find(
            tenantId: TenantId,
            clientId: String,
        ): Client? = no("ClientRepository")

        override suspend fun list(tenantId: TenantId): List<Client> = no("ClientRepository")

        override suspend fun upsert(client: Client) = no("ClientRepository")

        override suspend fun delete(
            tenantId: TenantId,
            clientId: String,
        ) = no("ClientRepository")
    }

    object Keys : KeyRepository {
        override suspend fun active(tenantId: TenantId): SigningKeyRecord? = no("KeyRepository")

        override suspend fun published(tenantId: TenantId): List<SigningKeyRecord> = no("KeyRepository")

        override suspend fun all(tenantId: TenantId): List<SigningKeyRecord> = no("KeyRepository")

        override suspend fun save(record: SigningKeyRecord) = no("KeyRepository")

        override suspend fun updateState(
            tenantId: TenantId,
            kid: String,
            state: KeyState,
        ) = no("KeyRepository")
    }

    object Attempts : LoginAttemptRepository {
        override suspend fun find(
            tenantId: TenantId,
            login: String,
        ): LoginAttempt? = no("LoginAttemptRepository")

        override suspend fun save(attempt: LoginAttempt) = no("LoginAttemptRepository")

        override suspend fun reset(
            tenantId: TenantId,
            login: String,
        ) = no("LoginAttemptRepository")
    }
}
