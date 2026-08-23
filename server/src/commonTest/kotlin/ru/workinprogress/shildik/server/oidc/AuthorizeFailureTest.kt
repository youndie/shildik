package ru.workinprogress.shildik.server.oidc

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.workinprogress.shildik.core.config.ShildikConfig
import ru.workinprogress.shildik.core.di.coreModule
import ru.workinprogress.shildik.core.di.domainModule
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.core.feature.auth.InteractiveAuthMethod
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.PendingAuthorization
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.AuthorizationCodeRepository
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.KeyRepository
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import ru.workinprogress.shildik.core.port.PendingAuthorizationRepository
import ru.workinprogress.shildik.core.port.RefreshTokenRepository
import ru.workinprogress.shildik.core.port.StorageHealth
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.UserRepository
import ru.workinprogress.shildik.server.ErrorReporter
import ru.workinprogress.shildik.server.publicModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the authorization endpoint says when it refuses.
 *
 * This route used to classify its own failures — `(error as? OAuthRejection)?.error ?:
 * "server_error"` — while every other route asked [OAuthFailure]. Anything the hand-written version
 * had not heard of came out as `server_error` with status `400`: a fault of ours, announced to the
 * client, and reported to nobody. A scope the client was never granted landed there the day scopes
 * shipped, and the person configuring it read "the provider is broken".
 *
 * So the refusal is asserted by **name** here, not by status: a 400 was there before and said
 * nothing true.
 */
class AuthorizeFailureTest {
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
                    single<ClientRepository> { OnePage }
                    single<PendingAuthorizationRepository> { Parking }
                    single<StorageHealth> { StorageHealth { true } }
                    single<AuthorizationCodeRepository> { Unused.Codes }
                    single<RefreshTokenRepository> { Unused.RefreshTokens }
                    single<UserRepository> { Unused.Users }
                    single<CredentialRepository> { Unused.Credentials }
                    single<KeyRepository> { Unused.Keys }
                    single<LoginAttemptRepository> { Unused.Attempts }
                    single<ErrorReporter> { ErrorReporter.Logging }
                    single { AuthMethodRegistry(listOf(Form)) }
                },
            )
        }.koin

    private fun url(scope: String) =
        "/realms/${OneRealm.realm}/oauth2/authorize" +
            "?response_type=code&client_id=${OnePage.clientId}" +
            "&redirect_uri=https%3A%2F%2Fpage.example%2F" +
            "&code_challenge=whatever&code_challenge_method=S256" +
            "&scope=$scope"

    @Test
    fun `a scope the client was never granted is named rather than blamed on us`() =
        testApplication {
            application { publicModule(koin) }

            val response = client.get(url("openid%20tasks%3Adelete"))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("invalid_scope"),
                "the refusal has to name the missing permission: ${response.bodyAsText()}",
            )
        }

    @Test
    fun `a granted scope reaches the sign-in form`() =
        testApplication {
            application { publicModule(koin) }

            val response = client.get(url("openid%20tasks%3Aread"))

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("<form"), "the person is supposed to get a form here")
        }

    @Test
    fun `the protocol scopes every browser client sends need no grant`() =
        testApplication {
            application { publicModule(koin) }

            val response = client.get(url("openid%20profile%20email"))

            assertEquals(HttpStatusCode.OK, response.status, "requiring a grant for these refuses every sign-in")
        }
}

private object Form : InteractiveAuthMethod {
    override val id: String = "form"

    override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject? = null
}

private object OnePage : ClientRepository {
    val clientId: String = "page"

    private val client =
        Client(
            tenantId = TenantId("t-1"),
            clientId = clientId,
            secretHash = "",
            roles = emptySet(),
            public = true,
            redirectUris = setOf("https://page.example/"),
            scopes = setOf("tasks:read"),
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

/** Accepts a parked request and remembers nothing: no test here comes back for one. */
private object Parking : PendingAuthorizationRepository {
    override suspend fun save(pending: PendingAuthorization) = Unit

    override suspend fun take(
        tenantId: TenantId,
        state: String,
    ): PendingAuthorization? = null

    override suspend fun find(
        tenantId: TenantId,
        state: String,
    ): PendingAuthorization? = null

    override suspend fun delete(
        tenantId: TenantId,
        state: String,
    ) = Unit
}
