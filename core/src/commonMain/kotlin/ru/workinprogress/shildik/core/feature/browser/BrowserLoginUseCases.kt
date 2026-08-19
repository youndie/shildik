package ru.workinprogress.shildik.core.feature.browser

import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.core.feature.admin.NotFound
import ru.workinprogress.shildik.core.feature.auth.AuthMethod
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.core.feature.auth.InteractiveAuthMethod
import ru.workinprogress.shildik.core.feature.auth.RedirectingAuthMethod
import ru.workinprogress.shildik.core.feature.token.VerifyOwnTokenUseCase
import ru.workinprogress.shildik.core.model.AuthorizationCode
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.PendingAuthorization
import ru.workinprogress.shildik.core.model.RefreshToken
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.core.port.AuthorizationCodeRepository
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import ru.workinprogress.shildik.core.port.PendingAuthorizationRepository
import ru.workinprogress.shildik.core.port.RefreshTokenRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.TransactionManager
import ru.workinprogress.shildik.core.port.UserRepository
import ru.workinprogress.shildik.core.usecase.UseCase
import ru.workinprogress.shildik.core.usecase.suspendRunCatching
import ru.workinprogress.shildik.crypto.Pkce
import ru.workinprogress.shildik.crypto.Secrets
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The request is refused by OAuth2 rules: what leaves is the spec's `error`, not our text. */
class OAuthRejection(
    val error: String,
    override val message: String,
) : Exception(message)

/**
 * Issuing an authorization code.
 *
 * Three checks meet here, and each closes its own attack:
 *
 * * **redirect_uri by exact match** — otherwise the code travels to somebody else's address;
 * * **PKCE is mandatory for a public client** — it has no secret, and without a verifier an
 *   intercepted code can be exchanged by anyone;
 * * **the identity is confirmed by an `AuthMethod`**, not by us — the shared sign-in code does not
 *   know whether it was Google or a link in an email, and must not know (feature-extensibility).
 */
@OptIn(ExperimentalUuidApi::class)
class AuthorizeUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val users: UserRepository,
    private val codes: AuthorizationCodeRepository,
    private val methods: AuthMethodRegistry,
    private val transactions: TransactionManager,
) : UseCase<AuthorizeUseCase.Params, IssuedCode> {
    override suspend fun invoke(params: Params): Result<IssuedCode> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val client =
                clients.find(tenant.id, params.clientId)
                    ?: throw OAuthRejection("unauthorized_client", "unknown client")

            if (params.responseType != "code") {
                throw OAuthRejection("unsupported_response_type", "only response_type=code is supported")
            }
            if (!client.allowsRedirect(params.redirectUri)) {
                // The error is **not** redirected back: sending it to an unverified address would
                // be exactly what we have just forbidden.
                throw OAuthRejection("invalid_request", "redirect_uri is not in the allowed list")
            }
            if (client.public && params.codeChallenge.isBlank()) {
                throw OAuthRejection("invalid_request", "PKCE is mandatory for a public client")
            }
            if (params.codeChallenge.isNotBlank() && params.codeChallengeMethod != Pkce.METHOD_S256) {
                // `plain` is allowed by the spec and gives no protection: there the challenge
                // equals the verifier.
                throw OAuthRejection("invalid_request", "only S256 is supported")
            }

            val method =
                methods.find(params.methodId)
                    ?: throw OAuthRejection("invalid_request", "sign-in method '${params.methodId}' is not wired in")

            val subject =
                method.authenticate(AuthRequest(params.realm, params.methodParameters))
                    ?: throw OAuthRejection("access_denied", "the identity was not confirmed")

            val identity = ExternalIdentity(method.id, subject.externalId)
            val code = Secrets.generate()

            transactions.withTransaction {
                // The person may have been imported from the previous provider — then they
                // already have an identifier and a new one must not be issued: a relying service
                // recognises them by `sub` (feature-user-import §2).
                val user =
                    users.findByIdentity(tenant.id, identity)
                        ?: linkedByEmail(tenant.id, method, subject)
                        ?: newUser(tenant, identity, subject)

                if (!user.enabled) throw OAuthRejection("access_denied", "sign-in is denied")

                codes.save(
                    AuthorizationCode(
                        tenantId = tenant.id,
                        codeHash = Secrets.hash(code),
                        clientId = client.clientId,
                        userId = user.id,
                        redirectUri = params.redirectUri,
                        codeChallenge = params.codeChallenge,
                        scope = params.scope,
                        nonce = params.nonce,
                        expiresAt = Clock.System.now() + CODE_TTL,
                    ),
                )

                IssuedCode(code = code, redirectUri = params.redirectUri, state = params.state)
            }
        }

    /**
     * A person found by a verified email rather than by the sign-in method's identity.
     *
     * Needed precisely for the magic link: those imported from Keycloak carry a `google` identity,
     * and nobody carries `magic` — magic-link accounts were created there without a federated link.
     * Without this lookup, signing in by link would create a **second** user with a `sub` like
     * `owner@example.com`, and the relying service would show an empty account
     * (feature-magic-link §2).
     *
     * Linking is allowed only when ownership of the email is proven **in this sign-in**. Otherwise
     * it is account takeover: sign in with somebody else's address and you are in their account.
     * Google does not always verify an email, so its answer decides — not the mere fact that the
     * sign-in went through Google.
     *
     * The record that was found gains an identity, but its `id` does not change: it has travelled
     * into tokens and into other people's databases, and must not be rewritten.
     */

    private suspend fun linkedByEmail(
        tenantId: TenantId,
        method: AuthMethod,
        subject: AuthenticatedSubject,
    ): User? {
        if (!subject.emailVerified) return null
        val email = subject.email ?: return null
        val existing = users.findByEmail(tenantId, email) ?: return null

        val linked =
            existing.copy(
                identities = existing.identities + ExternalIdentity(method.id, subject.externalId),
                emailVerified = true,
            )
        users.upsert(linked)
        return linked
    }

    /**
     * The first sign-in of a person we do not know yet.
     *
     * A closed tenant has no such sign-in: a confirmed identity and the right to enter are
     * different things, and an external provider answers only the first. For the internal contour
     * this is fundamental: monitoring sits behind it, and "has a Google account" must not open
     * access to it (feature-closed-registration).
     */
    private suspend fun newUser(
        tenant: Tenant,
        identity: ExternalIdentity,
        subject: AuthenticatedSubject,
    ): User {
        if (!tenant.registrationOpen) {
            throw OAuthRejection("access_denied", "sign-in is for provisioned users only")
        }

        return User(
            tenantId = tenant.id,
            // **Our own** identifier, not a foreign one. The magic link's external key is the
            // email, and taking it as `sub` would make changing an email into changing the person
            // for every relying service. External keys live in `identities`, where they belong.
            id = Uuid.random().toString(),
            email = subject.email,
            name = subject.name,
            emailVerified = subject.emailVerified,
            enabled = true,
            identities = setOf(identity),
        ).also { users.upsert(it) }
    }

    class Params(
        val realm: String,
        val clientId: String,
        val redirectUri: String,
        val responseType: String,
        val scope: String,
        val state: String?,
        val nonce: String?,
        val codeChallenge: String,
        val codeChallengeMethod: String?,
        val methodId: String,
        val methodParameters: Map<String, String>,
    )

    companion object {
        /**
         * A minute, not an hour: the code lives exactly as long as the redirect back takes.
         * Anything longer is a window for whoever peeked at it.
         */
        val CODE_TTL = 1.minutes
    }
}

/**
 * The start of a sign-in through an external provider.
 *
 * Every client check happens **here**, before leaving for Google: bringing a person back with an
 * error after a round trip is the worst possible way to say that the `redirect_uri` is wrong.
 */
class StartAuthorizationUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val pending: PendingAuthorizationRepository,
    private val methods: AuthMethodRegistry,
) : UseCase<StartAuthorizationUseCase.Params, StartedAuthorization> {
    override suspend fun invoke(params: Params): Result<StartedAuthorization> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val client =
                clients.find(tenant.id, params.clientId)
                    ?: throw OAuthRejection("unauthorized_client", "unknown client")

            if (params.responseType != "code") {
                throw OAuthRejection("unsupported_response_type", "only response_type=code is supported")
            }
            if (!client.allowsRedirect(params.redirectUri)) {
                throw OAuthRejection("invalid_request", "redirect_uri is not in the allowed list")
            }
            if (client.public && params.codeChallenge.isBlank()) {
                throw OAuthRejection("invalid_request", "PKCE is mandatory for a public client")
            }
            if (params.codeChallenge.isNotBlank() && params.codeChallengeMethod != Pkce.METHOD_S256) {
                throw OAuthRejection("invalid_request", "only S256 is supported")
            }

            // Two kinds park the request: the one that takes the person off to another provider,
            // and the one that needs our form. The only difference is what to return to the
            // caller.
            val method =
                methods.find(params.methodId)
                    ?: throw OAuthRejection("invalid_request", "sign-in method '${params.methodId}' is not wired in")
            if (method !is RedirectingAuthMethod && method !is InteractiveAuthMethod) {
                throw OAuthRejection("invalid_request", "sign-in method '${params.methodId}' needs no parked request")
            }

            // Our own `state`, not the client's: the client's comes back to it at the end, while
            // this one is the key to the parked request, and another provider knowing it is
            // enough.
            val state = Secrets.generate()
            pending.save(
                PendingAuthorization(
                    tenantId = tenant.id,
                    state = state,
                    clientId = client.clientId,
                    redirectUri = params.redirectUri,
                    scope = params.scope,
                    clientState = params.state,
                    nonce = params.nonce,
                    codeChallenge = params.codeChallenge,
                    methodId = method.id,
                    expiresAt = Clock.System.now() + PENDING_TTL,
                ),
            )

            StartedAuthorization(
                upstreamUrl = (method as? RedirectingAuthMethod)?.authorizationUrl(params.callbackUri, state),
                state = state,
            )
        }

    class Params(
        val realm: String,
        val clientId: String,
        val redirectUri: String,
        val responseType: String,
        val scope: String,
        val state: String?,
        val nonce: String?,
        val codeChallenge: String,
        val codeChallengeMethod: String?,
        val methodId: String,
        val callbackUri: String,
    )

    companion object {
        /** Ten minutes: that is how long a person may fumble on Google's screen, and no more. */
        val PENDING_TTL = 10.minutes
    }
}

data class StartedAuthorization(
    /** `null` means the method takes them nowhere: we will ask ourselves, with a form. */
    val upstreamUrl: String?,
    val state: String,
)

/**
 * The return from an external provider: find the parked request and carry the sign-in to a code.
 */
class CompleteAuthorizationUseCase(
    private val tenants: TenantRepository,
    private val pending: PendingAuthorizationRepository,
    private val authorize: AuthorizeUseCase,
) : UseCase<CompleteAuthorizationUseCase.Params, IssuedCode> {
    override suspend fun invoke(params: Params): Result<IssuedCode> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val stored =
                pending.take(tenant.id, params.state)
                    ?: throw OAuthRejection("invalid_request", "the request is unknown or stale")

            if (stored.expiresAt <= Clock.System.now()) {
                throw OAuthRejection("invalid_request", "the request is stale")
            }

            authorize(
                AuthorizeUseCase.Params(
                    realm = params.realm,
                    clientId = stored.clientId,
                    redirectUri = stored.redirectUri,
                    responseType = "code",
                    scope = stored.scope,
                    state = stored.clientState,
                    nonce = stored.nonce,
                    codeChallenge = stored.codeChallenge,
                    codeChallengeMethod = Pkce.METHOD_S256,
                    methodId = stored.methodId,
                    methodParameters = params.callbackParameters,
                ),
            ).getOrThrow()
        }

    class Params(
        val realm: String,
        val state: String,
        val callbackParameters: Map<String, String>,
    )
}

data class IssuedCode(
    val code: String,
    val redirectUri: String,
    val state: String?,
)

/**
 * Exchanging a code for tokens.
 *
 * Single use is enforced by marking it in the same transaction as the issuance: two simultaneous
 * exchanges must not yield two tokens. The PKCE check runs **before** the mark — otherwise a wrong
 * verifier would burn somebody else's code.
 */
class ExchangeCodeUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val users: UserRepository,
    private val codes: AuthorizationCodeRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val transactions: TransactionManager,
) : UseCase<ExchangeCodeUseCase.Params, ExchangedCode> {
    override suspend fun invoke(params: Params): Result<ExchangedCode> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val client =
                clients.find(tenant.id, params.clientId)
                    ?: throw OAuthRejection("invalid_client", "unknown client")

            val stored =
                codes.find(tenant.id, Secrets.hash(params.code))
                    ?: throw OAuthRejection("invalid_grant", "unknown code")

            // Everything below answers with the same error outwards: differences in the answers
            // would otherwise reveal which code exists.
            if (stored.used) throw OAuthRejection("invalid_grant", "the code was already used")
            if (stored.expiresAt <= Clock.System.now()) throw OAuthRejection("invalid_grant", "the code expired")
            if (stored.clientId != client.clientId) throw OAuthRejection("invalid_grant", "the code was issued to another client")
            if (stored.redirectUri != params.redirectUri) {
                throw OAuthRejection("invalid_grant", "redirect_uri differs from the one the code was issued for")
            }
            if (stored.codeChallenge.isNotBlank() && !Pkce.matches(stored.codeChallenge, params.codeVerifier.orEmpty())) {
                throw OAuthRejection("invalid_grant", "code_verifier does not match")
            }

            transactions.withTransaction {
                if (!codes.markUsed(tenant.id, stored.codeHash)) {
                    throw OAuthRejection("invalid_grant", "the code was already used")
                }
                val user =
                    users.find(tenant.id, stored.userId)
                        ?: throw OAuthRejection("invalid_grant", "the code's user was not found")

                // A refresh token is issued only when `offline_access` was asked for: handing a
                // long-lived secret to somebody who did not ask is risk without benefit.
                val refresh =
                    if (OFFLINE_ACCESS in stored.scope.split(' ')) {
                        Secrets.generate().also { token ->
                            refreshTokens.save(
                                RefreshToken(
                                    tenantId = tenant.id,
                                    tokenHash = Secrets.hash(token),
                                    family = Secrets.generate(),
                                    clientId = client.clientId,
                                    userId = user.id,
                                    scope = stored.scope,
                                    expiresAt = Clock.System.now() + RefreshTokensUseCase.REFRESH_TTL,
                                ),
                            )
                        }
                    } else {
                        null
                    }

                ExchangedCode(
                    user = user,
                    scope = stored.scope,
                    nonce = stored.nonce,
                    clientId = client.clientId,
                    refreshToken = refresh,
                )
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
        val code: String,
        val redirectUri: String,
        val codeVerifier: String?,
    )

    companion object {
        const val OFFLINE_ACCESS = "offline_access"
    }
}

/**
 * Refreshing tokens.
 *
 * **Rotation with replay detection.** Every exchange spends the presented token and issues a new
 * one. If an already spent token is presented, somebody saved it and is using it alongside the
 * rightful owner; there is no honest explanation for that. The whole family is revoked: a user will
 * survive signing in again, whereas continuing to issue tokens to whoever presented something
 * stolen is not acceptable.
 *
 * No client secret is asked for here: a public client is public, and demanding a secret from it
 * means demanding what does not exist. The binding is to the `clientId` and to the chain itself.
 */
class RefreshTokensUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val transactions: TransactionManager,
    private val clock: Clock = Clock.System,
) : UseCase<RefreshTokensUseCase.Params, RefreshedSession> {
    override suspend fun invoke(params: Params): Result<RefreshedSession> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val client =
                clients.find(tenant.id, params.clientId)
                    ?: throw OAuthRejection("invalid_client", "unknown client")

            val stored =
                refreshTokens.find(tenant.id, Secrets.hash(params.refreshToken))
                    ?: throw OAuthRejection("invalid_grant", "unknown token")

            // A repeat presentation is a sign of a leak. But **not every one**: a client whose
            // page is rendered by several server components refreshes the session in parallel, and
            // two requests with the same token arrive within milliseconds. The first version
            // revoked the whole chain at that point — together with the token just issued to the
            // winner of the race. The owner ended up with an irrevocably dead session and the shop
            // creation wizard (migration-lessons §1.6).
            //
            // So "repeat" counts only outside a short window. Inside the window the loser gets its
            // own token from the same family: two live branches for a few seconds are a lesser evil
            // than random sign-outs for everyone.
            val usedAt = stored.usedAt
            val concurrent =
                stored.used && usedAt != null && clock.now() - usedAt <= CONCURRENT_REFRESH_WINDOW

            if (stored.used && !concurrent) {
                refreshTokens.revokeFamily(tenant.id, stored.family)
                throw OAuthRejection("invalid_grant", "the token was presented twice")
            }
            if (stored.clientId != client.clientId) throw OAuthRejection("invalid_grant", "the token was issued to another client")
            if (stored.expiresAt <= clock.now()) throw OAuthRejection("invalid_grant", "the token expired")

            transactions.withTransaction {
                // Losing this race means somebody marked the token between our read and our
                // write — that is, within milliseconds. By definition that is a parallel refresh,
                // not a leak.
                refreshTokens.markUsed(tenant.id, stored.tokenHash)

                val user =
                    users.find(tenant.id, stored.userId)
                        ?: throw OAuthRejection("invalid_grant", "the token's user was not found")
                if (!user.enabled) throw OAuthRejection("invalid_grant", "sign-in is denied")

                val next = Secrets.generate()
                refreshTokens.save(
                    stored.copy(
                        tokenHash = Secrets.hash(next),
                        used = false,
                        expiresAt = clock.now() + REFRESH_TTL,
                    ),
                )

                RefreshedSession(user = user, clientId = client.clientId, scope = stored.scope, refreshToken = next)
            }
        }

    class Params(
        val realm: String,
        val clientId: String,
        val refreshToken: String,
    )

    companion object {
        /** Thirty days: that is the life of "remember me", and there is no point holding longer. */
        val REFRESH_TTL = 30.days

        /**
         * How long after being presented a token is still accepted as a parallel refresh.
         *
         * Ten seconds: that is how long it takes to render a page whose server components each
         * refresh the session on their own. Longer is a window for a leaked token, shorter brings
         * back sign-outs on a slow page.
         */
        val CONCURRENT_REFRESH_WINDOW = 10.seconds
    }
}

/**
 * Signing out.
 *
 * Revokes the refresh-token chain grown from this sign-in: without it, "signed out" only means "the
 * application forgot the token" while a refresh issued a month ago would keep working.
 *
 * The return address is checked against the client's list — the same one as for the code. Somebody
 * else's `post_logout_redirect_uri` is an open redirect from our domain, that is, a ready-made
 * phishing tool: the link starts with the provider's address and leads anywhere at all.
 */
class EndSessionUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val verifyToken: VerifyOwnTokenUseCase,
) : UseCase<EndSessionUseCase.Params, String?> {
    override suspend fun invoke(params: Params): Result<String?> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")

            val claims = params.idTokenHint?.let { verifyToken(tenant.id, it) }
            val clientId = (claims?.get("azp") as? JsonPrimitive)?.content
            val userId = (claims?.get("sub") as? JsonPrimitive)?.content

            if (clientId != null && userId != null) {
                refreshTokens.revokeForUser(tenant.id, clientId, userId)
            }

            val requested = params.postLogoutRedirectUri ?: return@suspendRunCatching null
            val client = clientId?.let { clients.find(tenant.id, it) }

            // No match — then no redirect at all. The sign-out still happened: the chain is
            // revoked.
            if (client != null && client.allowsRedirect(requested)) requested else null
        }

    class Params(
        val realm: String,
        val idTokenHint: String?,
        val postLogoutRedirectUri: String?,
    )
}

data class RefreshedSession(
    val user: User,
    val clientId: String,
    val scope: String,
    val refreshToken: String,
)

data class ExchangedCode(
    val user: User,
    val scope: String,
    val nonce: String?,
    val clientId: String,
    val refreshToken: String?,
)

/**
 * Submitting the sign-in form.
 *
 * It differs from a return from another provider in two ways, and both because the other side is a
 * person rather than another service.
 *
 * **The parked request is not consumed on failure.** A typo in a password must not send somebody
 * through the whole sign-in again — otherwise the form punishes people for the very thing it
 * exists for.
 *
 * **The refusal tells "wrong" from "locked".** A sign-in method is supposed to answer identically
 * outwards, but here the answer goes to somebody who has already typed their login: not telling
 * them about the lock leaves a person guessing for fifteen minutes. This gives away no account's
 * existence — the counter is kept for non-existent logins too.
 */
class SubmitLoginUseCase(
    private val tenants: TenantRepository,
    private val pending: PendingAuthorizationRepository,
    private val attempts: LoginAttemptRepository,
    private val authorize: AuthorizeUseCase,
    private val clock: Clock = Clock.System,
) : UseCase<SubmitLoginUseCase.Params, LoginOutcome> {
    override suspend fun invoke(params: Params): Result<LoginOutcome> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw NotFound("tenant '${params.realm}'")
            val stored = pending.find(tenant.id, params.state) ?: return@suspendRunCatching LoginOutcome.Expired
            if (stored.expiresAt <= clock.now()) {
                pending.delete(tenant.id, params.state)
                return@suspendRunCatching LoginOutcome.Expired
            }

            val issued =
                authorize(
                    AuthorizeUseCase.Params(
                        realm = params.realm,
                        clientId = stored.clientId,
                        redirectUri = stored.redirectUri,
                        responseType = "code",
                        scope = stored.scope,
                        state = stored.clientState,
                        nonce = stored.nonce,
                        codeChallenge = stored.codeChallenge,
                        codeChallengeMethod = Pkce.METHOD_S256,
                        methodId = stored.methodId,
                        methodParameters = params.formParameters,
                    ),
                ).getOrNull()

            if (issued == null) {
                val login =
                    params.formParameters[LOGIN_FIELD]
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                val locked = attempts.find(tenant.id, login)?.locked(clock.now()) == true
                return@suspendRunCatching if (locked) LoginOutcome.Locked else LoginOutcome.Wrong
            }

            pending.delete(tenant.id, params.state)
            LoginOutcome.Success(issued)
        }

    class Params(
        val realm: String,
        val state: String,
        val formParameters: Map<String, String>,
    )

    companion object {
        /** The form field the attempt counter is keyed by. Matches `PasswordAuthMethod`. */
        const val LOGIN_FIELD = "login"
    }
}

sealed interface LoginOutcome {
    data class Success(
        val issued: IssuedCode,
    ) : LoginOutcome

    /** Wrong login or wrong password — outwards these are one and the same state. */
    data object Wrong : LoginOutcome

    data object Locked : LoginOutcome

    data object Expired : LoginOutcome
}
