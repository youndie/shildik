package ru.workinprogress.shildik.core.feature.browser

import ru.workinprogress.shildik.core.feature.auth.AuthMethod
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.core.model.AuthorizationCode
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.RefreshToken
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.core.port.AuthorizationCodeRepository
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.RefreshTokenRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.TransactionManager
import ru.workinprogress.shildik.core.port.UserRepository
import kotlin.time.Clock

/**
 * In-memory storages: the domain is checked without a database — it decides nothing here, and
 * raising a container to check sign-in rules is out of proportion.
 */
class FakeTenants(
    private val tenants: List<Tenant>,
) : TenantRepository {
    override suspend fun byRealm(realm: String) = tenants.firstOrNull { it.realm == realm }

    override suspend fun byId(id: TenantId) = tenants.firstOrNull { it.id == id }

    override suspend fun list() = tenants

    override suspend fun create(tenant: Tenant) = tenant
}

class FakeClients(
    private val clients: MutableList<Client> = mutableListOf(),
) : ClientRepository {
    override suspend fun find(
        tenantId: TenantId,
        clientId: String,
    ) = clients.firstOrNull { it.tenantId == tenantId && it.clientId == clientId }

    override suspend fun list(tenantId: TenantId) = clients.filter { it.tenantId == tenantId }

    override suspend fun upsert(client: Client) {
        clients.removeAll { it.tenantId == client.tenantId && it.clientId == client.clientId }
        clients += client
    }

    override suspend fun delete(
        tenantId: TenantId,
        clientId: String,
    ) {
        clients.removeAll { it.tenantId == tenantId && it.clientId == clientId }
    }
}

class FakeUsers(
    val users: MutableList<User> = mutableListOf(),
) : UserRepository {
    override suspend fun find(
        tenantId: TenantId,
        id: String,
    ) = users.firstOrNull { it.tenantId == tenantId && it.id == id }

    override suspend fun findByIdentity(
        tenantId: TenantId,
        identity: ExternalIdentity,
    ) = users.firstOrNull { it.tenantId == tenantId && identity in it.identities }

    override suspend fun findByEmail(
        tenantId: TenantId,
        email: String,
    ) = users.firstOrNull { it.tenantId == tenantId && it.email == email }

    override suspend fun list(tenantId: TenantId) = users.filter { it.tenantId == tenantId }

    override suspend fun upsert(user: User) {
        users.removeAll { it.tenantId == user.tenantId && it.id == user.id }
        users += user
    }
}

class FakeCodes : AuthorizationCodeRepository {
    private val codes = mutableListOf<AuthorizationCode>()

    override suspend fun save(code: AuthorizationCode) {
        codes += code
    }

    override suspend fun find(
        tenantId: TenantId,
        codeHash: String,
    ) = codes.firstOrNull { it.tenantId == tenantId && it.codeHash == codeHash }

    override suspend fun markUsed(
        tenantId: TenantId,
        codeHash: String,
    ): Boolean {
        val index = codes.indexOfFirst { it.tenantId == tenantId && it.codeHash == codeHash && !it.used }
        if (index < 0) return false
        codes[index] = codes[index].copy(used = true)
        return true
    }
}

class FakeRefreshTokens(
    private val clock: Clock = Clock.System,
) : RefreshTokenRepository {
    private val tokens = mutableListOf<RefreshToken>()

    override suspend fun save(token: RefreshToken) {
        tokens += token
    }

    override suspend fun find(
        tenantId: TenantId,
        tokenHash: String,
    ) = tokens.firstOrNull { it.tenantId == tenantId && it.tokenHash == tokenHash }

    override suspend fun markUsed(
        tenantId: TenantId,
        tokenHash: String,
    ): Boolean {
        val i = tokens.indexOfFirst { it.tenantId == tenantId && it.tokenHash == tokenHash && !it.used }
        if (i < 0) return false
        tokens[i] = tokens[i].copy(used = true, usedAt = clock.now())
        return true
    }

    override suspend fun revokeForUser(
        tenantId: TenantId,
        clientId: String,
        userId: String,
    ) {
        tokens.indices.forEach { i ->
            val t = tokens[i]
            if (t.tenantId == tenantId && t.clientId == clientId && t.userId == userId) {
                tokens[i] = t.copy(used = true)
            }
        }
    }

    override suspend fun revokeFamily(
        tenantId: TenantId,
        family: String,
    ) {
        // `replaceAll` needs an opt-in on native — we make do with indices, which suits tests.
        tokens.indices.forEach { i ->
            val t = tokens[i]
            if (t.tenantId == tenantId && t.family == family) tokens[i] = t.copy(used = true)
        }
    }
}

class DirectTransactions : TransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
}

/** A sign-in method that always confirms one and the same person. */
class FakeAuthMethod(
    override val id: String = "google",
    private val subject: AuthenticatedSubject? = AuthenticatedSubject("google-sub-1", "owner@example.com", "Owner"),
) : AuthMethod {
    override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject? = subject
}

class FakeCredentials : CredentialRepository {
    private val hashes = mutableMapOf<Pair<TenantId, String>, String>()

    override suspend fun find(
        tenantId: TenantId,
        userId: String,
    ) = hashes[tenantId to userId]

    override suspend fun put(
        tenantId: TenantId,
        userId: String,
        passwordHash: String,
    ) {
        hashes[tenantId to userId] = passwordHash
    }

    override suspend fun delete(
        tenantId: TenantId,
        userId: String,
    ) {
        hashes.remove(tenantId to userId)
    }
}
