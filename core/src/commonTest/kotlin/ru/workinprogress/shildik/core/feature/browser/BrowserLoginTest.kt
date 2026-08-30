package ru.workinprogress.shildik.core.feature.browser

import kotlinx.coroutines.test.runTest
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Browser sign-in: the authorization code and its exchange.
 *
 * The tests are written from the **attacks**, not from the happy path: a public client has no
 * secret, and everything standing between an intercepted code and somebody else's account is the
 * checks below.
 */
class BrowserLoginTest {
    private val tenantId = TenantId("t1")
    private val realm = "main"
    private val redirect = "https://app.example.com/api/auth/callback/oidc"

    // The pair from RFC 7636: a verifier and its S256.
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    /** A clock we can move: without one, "leaked token" and "parallel request" are the same. */
    private class TestClock(
        var now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ) : Clock {
        override fun now() = now
    }

    private fun fixture(
        client: Client = publicClient(),
        users: FakeUsers = FakeUsers(),
        method: FakeAuthMethod = FakeAuthMethod(),
        clock: TestClock = TestClock(),
        registrationOpen: Boolean = true,
    ): Fixture {
        val tenants = FakeTenants(listOf(Tenant(tenantId, realm, registrationOpen)))
        val clients = FakeClients(mutableListOf(client))
        val codes = FakeCodes()
        val refresh = FakeRefreshTokens(clock)
        val tx = DirectTransactions()
        return Fixture(
            authorize = AuthorizeUseCase(tenants, clients, users, codes, AuthMethodRegistry(listOf(method)), tx),
            exchange = ExchangeCodeUseCase(tenants, clients, users, codes, refresh, tx),
            refresh = RefreshTokensUseCase(tenants, clients, users, refresh, tx, clock),
            users = users,
            clock = clock,
        )
    }

    private class Fixture(
        val authorize: AuthorizeUseCase,
        val exchange: ExchangeCodeUseCase,
        val refresh: RefreshTokensUseCase,
        val users: FakeUsers,
        val clock: TestClock,
    )

    private fun publicClient() =
        Client(
            tenantId = tenantId,
            clientId = "web-app",
            secretHash = "",
            roles = emptySet(),
            public = true,
            redirectUris = setOf(redirect),
        )

    private fun authorizeParams(
        challengeValue: String = challenge,
        redirectUri: String = redirect,
        method: String = "S256",
        methodId: String = "google",
    ) = AuthorizeUseCase.Params(
        realm = realm,
        clientId = "web-app",
        redirectUri = redirectUri,
        responseType = "code",
        scope = "openid profile email offline_access",
        state = "st",
        nonce = "n1",
        codeChallenge = challengeValue,
        codeChallengeMethod = method,
        methodId = methodId,
        methodParameters = emptyMap(),
    )

    private fun exchangeParams(
        code: String,
        verifierValue: String? = verifier,
        redirectUri: String = redirect,
    ) = ExchangeCodeUseCase.Params(
        realm = realm,
        clientId = "web-app",
        code = code,
        redirectUri = redirectUri,
        codeVerifier = verifierValue,
    )

    private fun refreshParams(token: String) = RefreshTokensUseCase.Params(realm, "web-app", token)

    private fun rejection(result: Result<*>): OAuthRejection {
        val error = result.exceptionOrNull()
        assertTrue(error is OAuthRejection, "expected an OAuth2 refusal, got: $error")
        return error
    }

    @Test
    fun `signing in issues a code which exchanges into a person`() =
        runTest {
            val f = fixture()

            val issued = f.authorize(authorizeParams()).getOrThrow()
            val exchanged = f.exchange(exchangeParams(issued.code)).getOrThrow()

            assertEquals("st", issued.state, "the state has to come back untouched")
            assertEquals("owner@example.com", exchanged.user.email)
            assertEquals("n1", exchanged.nonce, "the nonce is needed for the id_token")
        }

    @Test
    fun `the code is single-use`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()
            f.exchange(exchangeParams(issued.code)).getOrThrow()

            // A second exchange of the same code is the classic sign of interception.
            assertEquals("invalid_grant", rejection(f.exchange(exchangeParams(issued.code))).error)
        }

    @Test
    fun `somebody else's verifier does not exchange the code`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()

            // Exactly what PKCE is for: the code is there, the verifier is not.
            assertEquals("invalid_grant", rejection(f.exchange(exchangeParams(issued.code, "x".repeat(43)))).error)
        }

    @Test
    fun `a wrong verifier does not burn the code`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()

            f.exchange(exchangeParams(issued.code, "x".repeat(43)))

            // Otherwise presenting garbage would be enough to keep a person out.
            assertNotNull(f.exchange(exchangeParams(issued.code)).getOrNull())
        }

    @Test
    fun `the redirect_uri at exchange must match the one the code was issued for`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()

            assertEquals(
                "invalid_grant",
                rejection(f.exchange(exchangeParams(issued.code, redirectUri = "https://evil.example/cb"))).error,
            )
        }

    @Test
    fun `an unknown redirect_uri is refused`() =
        runTest {
            val f = fixture()

            assertEquals(
                "invalid_request",
                rejection(f.authorize(authorizeParams(redirectUri = "https://evil.example/cb"))).error,
            )
        }

    @Test
    fun `PKCE is mandatory for a public client`() =
        runTest {
            val f = fixture()

            assertEquals("invalid_request", rejection(f.authorize(authorizeParams(challengeValue = ""))).error)
        }

    @Test
    fun `plain is not accepted`() =
        runTest {
            val f = fixture()

            assertEquals("invalid_request", rejection(f.authorize(authorizeParams(method = "plain"))).error)
        }

    @Test
    fun `an imported person is recognised rather than created again`() =
        runTest {
            val keycloakId = "c0b45dd1-5c0d-4401-9af5-4c6221285551"
            val existing =
                User(
                    tenantId = tenantId,
                    id = keycloakId,
                    email = "owner@example.com",
                    name = "Owner",
                    emailVerified = true,
                    enabled = true,
                    identities = setOf(ExternalIdentity("google", "google-sub-1")),
                )
            val f = fixture(users = FakeUsers(mutableListOf(existing)))

            val issued = f.authorize(authorizeParams()).getOrThrow()
            val exchanged = f.exchange(exchangeParams(issued.code)).getOrThrow()

            // The milestone's main scenario: otherwise an owner signs in to an empty account.
            assertEquals(keycloakId, exchanged.user.id)
            assertEquals(1, f.users.users.size, "no new user should have been created")
        }

    @Test
    fun `a refresh issues a new token and spends the previous one`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()
            val first = f.exchange(exchangeParams(issued.code)).getOrThrow().refreshToken!!

            val refreshed = f.refresh(refreshParams(first)).getOrThrow()

            assertTrue(refreshed.refreshToken != first, "rotation has to issue a new token")
            // The previous one must stop working — otherwise rotation gives nothing. We check
            // outside the parallel-refresh window: inside it a repeat is legitimate.
            f.clock.now += 1.minutes
            assertEquals("invalid_grant", rejection(f.refresh(refreshParams(first))).error)
        }

    @Test
    fun `a parallel refresh does not kill the session`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()
            val first = f.exchange(exchangeParams(issued.code)).getOrThrow().refreshToken!!

            // This is how next-auth behaves: a page is rendered by several server components,
            // and each refreshes the session on its own. Both requests carry the same token.
            val a = f.refresh(refreshParams(first)).getOrThrow().refreshToken
            val b = f.refresh(refreshParams(first)).getOrThrow().refreshToken

            // Neither may be refused, and the chain has to stay alive: the second request used to
            // revoke the family together with the token just issued to the first.
            assertTrue(a != first && b != first, "both refreshes have to issue new tokens")
            f.refresh(refreshParams(b)).getOrThrow()
        }

    @Test
    fun `presenting a token twice revokes the whole chain`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()
            val first = f.exchange(exchangeParams(issued.code)).getOrThrow().refreshToken!!
            val second = f.refresh(refreshParams(first)).getOrThrow().refreshToken

            // Somebody saved the first token and presented it again — so it leaked. "Again" here
            // means "noticeably later": within seconds it is indistinguishable from a client
            // refreshing the session from several server components at once.
            f.clock.now += 1.minutes
            f.refresh(refreshParams(first))

            // So the one the rightful owner holds right now is revoked as well: signing in again
            // is cheaper than continuing to issue tokens to whoever presented something stolen.
            assertEquals("invalid_grant", rejection(f.refresh(refreshParams(second))).error)
        }

    @Test
    fun `a new person gets our identifier rather than a foreign key`() =
        runTest {
            // The magic link's external key is the email. Taking it as `sub` would make changing
            // an email into changing the person for every relying service.
            val users = FakeUsers()
            val f =
                fixture(
                    users = users,
                    method =
                        FakeAuthMethod(
                            id = "magic",
                            subject =
                                AuthenticatedSubject(
                                    "owner@example.com",
                                    "owner@example.com",
                                    emailVerified = true,
                                ),
                        ),
                )

            f.authorize(authorizeParams(methodId = "magic")).getOrThrow()

            val created = users.users.single()
            assertTrue(created.id != "owner@example.com", "the email became the identifier: ${created.id}")
            assertTrue(
                ExternalIdentity("magic", "owner@example.com") in created.identities,
                "the external key has to stay among the identities",
            )
            assertTrue(created.emailVerified, "the email was verified by the sign-in method")
        }

    @Test
    fun `in a closed tenant a stranger does not get in`() =
        runTest {
            // The internal contour: monitoring sits behind the provider, and "confirmed their
            // identity with Google" must not mean "may look at our metrics". A provider answers who
            // this is, not who is allowed.
            val users = FakeUsers()
            val f = fixture(users = users, registrationOpen = false)

            assertEquals("access_denied", rejection(f.authorize(authorizeParams())).error)
            assertEquals(0, users.users.size, "a closed tenant must not create a person")
        }

    @Test
    fun `in a closed tenant a provisioned person gets in`() =
        runTest {
            val known =
                User(
                    tenantId = tenantId,
                    id = "google-sub-1",
                    email = "owner@example.com",
                    name = "Owner",
                    emailVerified = true,
                    enabled = true,
                    identities = setOf(ExternalIdentity("google", "google-sub-1")),
                )
            val f = fixture(users = FakeUsers(mutableListOf(known)), registrationOpen = false)

            assertTrue(
                f
                    .authorize(authorizeParams())
                    .getOrThrow()
                    .code
                    .isNotBlank(),
            )
        }

    @Test
    fun `a sign-in with a verified email recognises an imported person`() =
        runTest {
            // People imported from Keycloak carry a `google` identity, and nobody carries `magic`.
            // Without linking by email, a sign-in by link would create a second person with a `sub`
            // like owner@example.com — and the relying service would show an empty account.
            val migrated =
                User(
                    tenantId = tenantId,
                    id = "keycloak-user-id",
                    email = "owner@example.com",
                    name = "Owner",
                    emailVerified = true,
                    enabled = true,
                    identities = setOf(ExternalIdentity("google", "google-sub-1")),
                )
            val users = FakeUsers(mutableListOf(migrated))
            val f =
                fixture(
                    users = users,
                    method =
                        FakeAuthMethod(
                            id = "magic",
                            subject =
                                AuthenticatedSubject(
                                    "owner@example.com",
                                    "owner@example.com",
                                    emailVerified = true,
                                ),
                        ),
                )

            val issued = f.authorize(authorizeParams(methodId = "magic")).getOrThrow()

            assertEquals(1, users.users.size, "linking must not create a second person")
            assertEquals("keycloak-user-id", users.users.single().id, "the identifier must not change")
            assertTrue(
                ExternalIdentity("magic", "owner@example.com") in users.users.single().identities,
                "the new sign-in method has to attach to the existing record",
            )
            assertTrue(issued.code.isNotBlank())
        }

    @Test
    fun `an unverified email does not link to somebody else's account`() =
        runTest {
            // Otherwise it is account takeover: sign in with somebody else's address and you are
            // in their account.
            val migrated =
                User(
                    tenantId = tenantId,
                    id = "keycloak-user-id",
                    email = "owner@example.com",
                    name = "Owner",
                    emailVerified = true,
                    enabled = true,
                    identities = setOf(ExternalIdentity("google", "google-sub-1")),
                )
            val users = FakeUsers(mutableListOf(migrated))
            val f =
                fixture(
                    users = users,
                    method =
                        FakeAuthMethod(
                            id = "magic",
                            subject =
                                AuthenticatedSubject(
                                    "owner@example.com",
                                    "owner@example.com",
                                    emailVerified = false,
                                ),
                        ),
                )

            f.authorize(authorizeParams(methodId = "magic")).getOrThrow()

            assertEquals(2, users.users.size, "without a proven email there must be no linking")
        }

    @Test
    fun `without offline_access no refresh token is issued`() =
        runTest {
            val f = fixture()
            val params = authorizeParams()
            val issued =
                f
                    .authorize(
                        AuthorizeUseCase.Params(
                            realm = params.realm,
                            clientId = params.clientId,
                            redirectUri = params.redirectUri,
                            responseType = params.responseType,
                            scope = "openid profile email",
                            state = params.state,
                            nonce = params.nonce,
                            codeChallenge = params.codeChallenge,
                            codeChallengeMethod = params.codeChallengeMethod,
                            methodId = params.methodId,
                            methodParameters = params.methodParameters,
                        ),
                    ).getOrThrow()

            // A long-lived secret for somebody who did not ask is risk without benefit.
            assertEquals(null, f.exchange(exchangeParams(issued.code)).getOrThrow().refreshToken)
        }

    @Test
    fun `another client cannot refresh the token`() =
        runTest {
            val f = fixture()
            val issued = f.authorize(authorizeParams()).getOrThrow()
            val token = f.exchange(exchangeParams(issued.code)).getOrThrow().refreshToken!!

            assertEquals(
                "invalid_client",
                rejection(f.refresh(RefreshTokensUseCase.Params(realm, "no-such-client", token))).error,
            )
        }

    @Test
    fun `a disabled person cannot sign in`() =
        runTest {
            val f =
                fixture(
                    users =
                        FakeUsers(
                            mutableListOf(
                                User(
                                    tenantId = tenantId,
                                    id = "u1",
                                    email = null,
                                    name = null,
                                    emailVerified = false,
                                    enabled = false,
                                    identities = setOf(ExternalIdentity("google", "google-sub-1")),
                                ),
                            ),
                        ),
                )

            assertEquals("access_denied", rejection(f.authorize(authorizeParams())).error)
        }

    @Test
    fun `an unconfirmed identity yields no code`() =
        runTest {
            val f = fixture(method = FakeAuthMethod(subject = null))

            assertEquals("access_denied", rejection(f.authorize(authorizeParams())).error)
        }
}
