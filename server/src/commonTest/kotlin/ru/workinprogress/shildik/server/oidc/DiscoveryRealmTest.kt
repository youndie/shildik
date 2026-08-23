package ru.workinprogress.shildik.server.oidc

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.workinprogress.shildik.core.config.ShildikConfig
import ru.workinprogress.shildik.core.di.coreModule
import ru.workinprogress.shildik.core.di.domainModule
import ru.workinprogress.shildik.core.model.Tenant
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
 * Discovery answers only for a realm that exists.
 *
 * Every address in the document is built out of the issuer, so a document for a realm nobody
 * created is indistinguishable from a real one. Discovery is also the first request any client
 * makes and the first thing anybody reads to check a configuration — which is what made the old
 * behaviour expensive: a mistyped realm read as a healthy provider, and the search for the cause
 * went to the keys, the network, CORS, anywhere but the name. It surfaced only at `jwks`, the first
 * address here that asks storage anything, as a `404 unknown_realm` that named a realm the client
 * believed it had just been told about.
 *
 * It was found by measuring a live contour with a realm that did not exist there — and believing
 * the answer.
 *
 * This is also the first test in this repository that raises the public contour rather than a
 * hand-made route, so it covers what [PageReadableTest] deliberately does not: that the discovery
 * endpoint really calls the helper, on the refusal as much as on the document. A page that cannot
 * read the refusal sees a network error instead of the reason.
 */
class DiscoveryRealmTest {
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
                    single<StorageHealth> { StorageHealth { true } }
                    // The rest of the ports are here because the graph asks for them, not because
                    // this test uses them: an implementation that answers would be a claim about
                    // behaviour nobody checks.
                    single<AuthorizationCodeRepository> { Unused.Codes }
                    single<PendingAuthorizationRepository> { Unused.Pending }
                    single<RefreshTokenRepository> { Unused.RefreshTokens }
                    single<UserRepository> { Unused.Users }
                    single<CredentialRepository> { Unused.Credentials }
                    single<ClientRepository> { Unused.Clients }
                    single<KeyRepository> { Unused.Keys }
                    single<LoginAttemptRepository> { Unused.Attempts }
                    single<ErrorReporter> { ErrorReporter.Logging }
                },
            )
        }.koin

    @Test
    fun `a realm nobody created is refused rather than described`() =
        testApplication {
            application { publicModule(koin) }

            val response = client.get("/realms/never-created/.well-known/openid-configuration")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(
                response.bodyAsText().contains("unknown_realm"),
                "the refusal has to name the realm as the reason: ${response.bodyAsText()}",
            )
            // The refusal is as much of an answer as the document, and a page has to be able to
            // read it — otherwise the developer sees a network error where a reason was sent.
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `a realm that exists is described`() =
        testApplication {
            application { publicModule(koin) }

            val response = client.get("/realms/${OneRealm.realm}/.well-known/openid-configuration")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"issuer\":\"${config.issuer}/realms/${OneRealm.realm}\""),
                "the document has to be about the realm that was asked for: ${response.bodyAsText()}",
            )
        }
}

internal object OneRealm : TenantRepository {
    val realm: String = "main"

    private val tenant = Tenant(id = TenantId("t-1"), realm = realm)

    override suspend fun byRealm(realm: String): Tenant? = tenant.takeIf { it.realm == realm }

    override suspend fun byId(id: TenantId): Tenant? = tenant.takeIf { it.id == id }

    override suspend fun list(): List<Tenant> = listOf(tenant)

    override suspend fun create(tenant: Tenant): Tenant = tenant
}
