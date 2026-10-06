package io.github.youndie.shildik.server.oidc

import io.github.youndie.shildik.core.config.ShildikConfig
import io.github.youndie.shildik.core.di.coreModule
import io.github.youndie.shildik.core.di.domainModule
import io.github.youndie.shildik.core.feature.auth.AuthMethod
import io.github.youndie.shildik.core.feature.auth.AuthMethodRegistry
import io.github.youndie.shildik.core.feature.auth.AuthRequest
import io.github.youndie.shildik.core.feature.auth.AuthenticatedSubject
import io.github.youndie.shildik.core.model.AuthorizationCode
import io.github.youndie.shildik.core.model.Client
import io.github.youndie.shildik.core.model.ExternalIdentity
import io.github.youndie.shildik.core.model.TenantId
import io.github.youndie.shildik.core.model.User
import io.github.youndie.shildik.core.port.AuthorizationCodeRepository
import io.github.youndie.shildik.core.port.ClientRepository
import io.github.youndie.shildik.core.port.CredentialRepository
import io.github.youndie.shildik.core.port.KeyRepository
import io.github.youndie.shildik.core.port.LoginAttemptRepository
import io.github.youndie.shildik.core.port.NoopTransactionManager
import io.github.youndie.shildik.core.port.PendingAuthorizationRepository
import io.github.youndie.shildik.core.port.RefreshTokenRepository
import io.github.youndie.shildik.core.port.StorageHealth
import io.github.youndie.shildik.core.port.TenantRepository
import io.github.youndie.shildik.core.port.TransactionManager
import io.github.youndie.shildik.core.port.UserRepository
import io.github.youndie.shildik.server.ErrorReporter
import io.github.youndie.shildik.server.publicModule
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.testApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An app's private-use scheme survives the redirect that carries the code (RFC 8252 §7.1).
 *
 * The address is matched against the client's list byte for byte and then handed to the HTTP layer.
 * What is checked here is the second half: that nothing between the match and the `Location` header
 * — our concatenation, Ktor's `respondRedirect` — rewrites an address whose scheme it does not know.
 * An app whose callback came back as `io.example.app:///callback` never receives its code, and the
 * person sees a browser tab that goes nowhere.
 *
 * The three routes that end in a code share [codeRedirect]; this test walks the one that confirms the
 * identity on the spot, the other two differ only in how they got to the same call.
 */
class AppRedirectTest {
    private val config =
        ShildikConfig(
            issuer = "https://shildik.example",
            publicPort = 8080,
            managementPort = 9000,
            masterKeys = listOf("test-master-key"),
        )

    private val koin =
        koinApplication {
            modules(
                coreModule(config),
                domainModule(),
                module {
                    single<TenantRepository> { OneRealm }
                    single<ClientRepository> { OneApp }
                    single<UserRepository> { Users() }
                    single<AuthorizationCodeRepository> { Codes() }
                    single<TransactionManager> { NoopTransactionManager }
                    single<StorageHealth> { StorageHealth { true } }
                    single<PendingAuthorizationRepository> { Unused.Pending }
                    single<RefreshTokenRepository> { Unused.RefreshTokens }
                    single<CredentialRepository> { Unused.Credentials }
                    single<KeyRepository> { Unused.Keys }
                    single<LoginAttemptRepository> { Unused.Attempts }
                    single<ErrorReporter> { ErrorReporter.Logging }
                    single { AuthMethodRegistry(listOf(OnTheSpot)) }
                },
            )
        }.koin

    private fun authorize(redirectUri: String) =
        "/realms/${OneRealm.realm}/oauth2/authorize" +
            "?response_type=code&client_id=${OneApp.clientId}" +
            "&redirect_uri=${redirectUri.encodeURLParameter()}" +
            "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256" +
            "&scope=openid&state=s%2F1"

    @Test
    fun `the code goes back to a private-use scheme exactly as registered`() =
        testApplication {
            application { publicModule(koin) }
            val noRedirects = createClient { followRedirects = false }

            for (uri in OneApp.redirects) {
                val response = noRedirects.get(authorize(uri))

                assertEquals(HttpStatusCode.Found, response.status, uri)
                val location = response.headers[HttpHeaders.Location].orEmpty()
                assertTrue(
                    location.startsWith("$uri?code="),
                    "the address must come back untouched — registered '$uri', got '$location'",
                )
                assertTrue(location.endsWith("&state=s%2F1"), "the state must come back: '$location'")
            }
        }

    @Test
    fun `the code is appended after a query the app registered`() {
        assertEquals(
            "io.example.app:/cb?flow=1&code=c&state=s",
            codeRedirect("io.example.app:/cb?flow=1", "c", "s"),
        )
    }
}

private object OnTheSpot : AuthMethod {
    override val id: String = "on-the-spot"

    override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject =
        AuthenticatedSubject("sub-1", "person@example.com", "Person")
}

private object OneApp : ClientRepository {
    val clientId: String = "app"
    val redirects = listOf("io.example.app:/oauth2/callback", "io.example.app://callback")

    private val client =
        Client(
            tenantId = TenantId("t-1"),
            clientId = clientId,
            secretHash = "",
            roles = emptySet(),
            public = true,
            redirectUris = redirects.toSet(),
        )

    override suspend fun find(
        tenantId: TenantId,
        clientId: String,
    ): Client? = client.takeIf { it.clientId == clientId }

    override suspend fun list(tenantId: TenantId): List<Client> = listOf(client)

    override suspend fun upsert(client: Client) = error("not part of this test")

    override suspend fun delete(
        tenantId: TenantId,
        clientId: String,
    ) = error("not part of this test")
}

private class Users : UserRepository {
    private val users = mutableListOf<User>()

    override suspend fun find(
        tenantId: TenantId,
        id: String,
    ): User? = users.firstOrNull { it.id == id }

    override suspend fun findByIdentity(
        tenantId: TenantId,
        identity: ExternalIdentity,
    ): User? = users.firstOrNull { identity in it.identities }

    override suspend fun findByEmail(
        tenantId: TenantId,
        email: String,
    ): User? = users.firstOrNull { it.email == email }

    override suspend fun list(tenantId: TenantId): List<User> = users.toList()

    override suspend fun upsert(user: User) {
        users.removeAll { it.id == user.id }
        users += user
    }
}

/** Keeps what was issued and answers nothing else: no test here exchanges a code. */
private class Codes : AuthorizationCodeRepository {
    override suspend fun save(code: AuthorizationCode) = Unit

    override suspend fun find(
        tenantId: TenantId,
        codeHash: String,
    ): AuthorizationCode? = error("not part of this test")

    override suspend fun markUsed(
        tenantId: TenantId,
        codeHash: String,
    ): Boolean = error("not part of this test")
}
