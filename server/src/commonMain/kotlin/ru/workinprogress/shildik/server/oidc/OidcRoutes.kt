package ru.workinprogress.shildik.server.oidc

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.resources.get
import io.ktor.server.resources.options
import io.ktor.server.resources.post
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.util.decodeBase64String
import kotlinx.serialization.json.JsonObject
import org.koin.core.Koin
import ru.workinprogress.shildik.core.di.IssuerResolver
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.core.feature.auth.InteractiveAuthMethod
import ru.workinprogress.shildik.core.feature.auth.RedirectingAuthMethod
import ru.workinprogress.shildik.core.feature.browser.AuthorizeUseCase
import ru.workinprogress.shildik.core.feature.browser.CompleteAuthorizationUseCase
import ru.workinprogress.shildik.core.feature.browser.EndSessionUseCase
import ru.workinprogress.shildik.core.feature.browser.ExchangeCodeUseCase
import ru.workinprogress.shildik.core.feature.browser.LoginOutcome
import ru.workinprogress.shildik.core.feature.browser.OAuthRejection
import ru.workinprogress.shildik.core.feature.browser.RefreshTokensUseCase
import ru.workinprogress.shildik.core.feature.browser.StartAuthorizationUseCase
import ru.workinprogress.shildik.core.feature.browser.SubmitLoginUseCase
import ru.workinprogress.shildik.core.feature.keys.GetJwksUseCase
import ru.workinprogress.shildik.core.feature.keys.UnknownRealm
import ru.workinprogress.shildik.core.feature.token.IssueServiceTokenUseCase
import ru.workinprogress.shildik.core.feature.token.IssueUserTokensUseCase
import ru.workinprogress.shildik.core.feature.token.Scopes
import ru.workinprogress.shildik.core.feature.token.VerifyOwnTokenUseCase
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.server.ErrorReporter
import ru.workinprogress.shildik.server.ui.LoginPage
import ru.workinprogress.shildik.server.ui.PICO_CSS
import ru.workinprogress.shildik.shared.DiscoveryDocument
import ru.workinprogress.shildik.shared.OAuth2
import ru.workinprogress.shildik.shared.OAuthError
import ru.workinprogress.shildik.shared.RealmResource
import ru.workinprogress.shildik.shared.TokenResponse

/**
 * The public OIDC contour.
 *
 * URLs are not spelled out here as strings — they arrive from `:shared` as types
 * (`RealmResource`), and the client uses the same description. The shape of a URL used to live in
 * two places and could drift apart silently.
 */
fun Application.oidcRoutes(koin: Koin) {
    val issueToken = koin.get<IssueServiceTokenUseCase>()
    val authorize = koin.get<AuthorizeUseCase>()
    val start = koin.get<StartAuthorizationUseCase>()
    val complete = koin.get<CompleteAuthorizationUseCase>()
    val submit = koin.get<SubmitLoginUseCase>()
    val methods = koin.get<AuthMethodRegistry>()
    val exchange = koin.get<ExchangeCodeUseCase>()
    val refresh = koin.get<RefreshTokensUseCase>()
    val issueUserTokens = koin.get<IssueUserTokensUseCase>()
    val tenants = koin.get<TenantRepository>()
    val getJwks = koin.get<GetJwksUseCase>()
    val issuers = koin.get<IssuerResolver>()
    val verifyToken = koin.get<VerifyOwnTokenUseCase>()
    val reporter = koin.get<ErrorReporter>()
    val endSession = koin.get<EndSessionUseCase>()

    suspend fun io.ktor.server.routing.RoutingContext.logout(
        realm: String,
        params: io.ktor.http.Parameters,
    ) {
        endSession(
            EndSessionUseCase.Params(
                realm = realm,
                idTokenHint = params["id_token_hint"],
                postLogoutRedirectUri = params["post_logout_redirect_uri"],
            ),
        ).fold(
            onSuccess = { redirect ->
                // Only redirect to an address from the client's list: somebody else's
                // post_logout_redirect_uri is an open redirect from our domain.
                if (redirect != null) call.respondRedirect(redirect) else call.respond(HttpStatusCode.NoContent)
            },
            onFailure = { call.respond(HttpStatusCode.BadRequest, OAuthError("invalid_request")) },
        )
    }

    suspend fun io.ktor.server.routing.RoutingContext.issueTokens(realm: String) {
        call.readableByAPage()
        val form = call.receiveParameters()
        val credentials = clientCredentials(call, form)

        when (form["grant_type"]) {
            "client_credentials" -> Unit

            "authorization_code" -> {
                exchangeCode(realm, form, credentials.clientId, reporter, exchange, tenants, issueUserTokens)
                return
            }

            "refresh_token" -> {
                refreshSession(realm, form, credentials.clientId, reporter, refresh, tenants, issueUserTokens)
                return
            }

            else -> {
                call.respond(HttpStatusCode.BadRequest, OAuthError("unsupported_grant_type"))
                return
            }
        }

        issueToken(
            IssueServiceTokenUseCase.Params(
                realm = realm,
                clientId = credentials.clientId,
                clientSecret = credentials.clientSecret,
                // RFC 8707 allows the parameter more than once, and a client that names two
                // resources means both — not the last one to arrive.
                resources = form.getAll("resource").orEmpty().toSet(),
                // One parameter, space-delimited (RFC 6749 §3.3) — unlike `resource` above, which
                // repeats. The two spellings are not ours to reconcile.
                scopes = Scopes.parse(form["scope"]),
            ),
        ).fold(
            onSuccess = { call.respond(TokenResponse(it.accessToken, it.expiresInSeconds, scope = it.scope)) },
            // A refusal to the client and a fault of ours answer **differently**. This used to
            // be one `invalid_client` for everything: an unreachable database looked to the client
            // like a bad secret, and nothing at all reached monitoring. The `client_credentials`
            // branch was missed when the others were fixed (M-52) — found during acceptance when
            // the database disappeared. Refusals themselves stay indistinguishable, see
            // `OAuthFailure`.
            onFailure = { error -> call.respondFailure(error, reporter) },
        )
    }

    suspend fun io.ktor.server.routing.RoutingContext.startAuthorization(realm: String) {
        val query = call.request.queryParameters

        val methodId = query["auth_method"] ?: methods.defaultId()

        // A redirecting method takes the person off to another provider and brings them back to
        // the callback; the others confirm the identity right here.
        val method = methods.find(methodId)
        if (method is RedirectingAuthMethod || method is InteractiveAuthMethod) {
            start(
                StartAuthorizationUseCase.Params(
                    realm = realm,
                    clientId = query["client_id"].orEmpty(),
                    redirectUri = query["redirect_uri"].orEmpty(),
                    responseType = query["response_type"].orEmpty(),
                    scope = query["scope"].orEmpty(),
                    state = query["state"],
                    nonce = query["nonce"],
                    codeChallenge = query["code_challenge"].orEmpty(),
                    codeChallengeMethod = query["code_challenge_method"],
                    methodId = methodId,
                    callbackUri = callbackUri(issuers, realm, methodId),
                ),
            ).fold(
                onSuccess = { started ->
                    val upstream = started.upstreamUrl
                    if (upstream != null) {
                        call.respondRedirect(upstream)
                    } else {
                        // We ask ourselves: a page with a form, while the request is already
                        // parked and lives under its own `state`.
                        call.respondLoginPage(loginPath(realm, methodId), started.state)
                    }
                },
                onFailure = { error ->
                    call.respond(HttpStatusCode.BadRequest, OAuthError((error as? OAuthRejection)?.error ?: "server_error"))
                },
            )
            return
        }

        authorize(
            AuthorizeUseCase.Params(
                realm = realm,
                clientId = query["client_id"].orEmpty(),
                redirectUri = query["redirect_uri"].orEmpty(),
                responseType = query["response_type"].orEmpty(),
                scope = query["scope"].orEmpty(),
                state = query["state"],
                nonce = query["nonce"],
                codeChallenge = query["code_challenge"].orEmpty(),
                codeChallengeMethod = query["code_challenge_method"],
                methodId = methodId,
                methodParameters = query.entries().associate { it.key to it.value.first() },
            ),
        ).fold(
            onSuccess = { issued ->
                val separator = if ('?' in issued.redirectUri) '&' else '?'
                val state = issued.state?.let { "&state=" + it.encodeURLParameter() }.orEmpty()
                call.respondRedirect("${issued.redirectUri}$separator" + "code=" + issued.code.encodeURLParameter() + state)
            },
            onFailure = { error ->
                val code = (error as? OAuthRejection)?.error ?: "server_error"
                call.respond(HttpStatusCode.BadRequest, OAuthError(code))
            },
        )
    }

    suspend fun io.ktor.server.routing.RoutingContext.completeCallback(
        realm: String,
        method: String,
    ) {
        val query = call.request.queryParameters

        complete(
            CompleteAuthorizationUseCase.Params(
                realm = realm,
                state = query["state"].orEmpty(),
                callbackParameters =
                    query.entries().associate { it.key to it.value.first() } +
                        // The other provider checks the return address when the code is
                        // exchanged: it has to be the same one we sent in the authorization
                        // request.
                        mapOf(CALLBACK_URI_PARAM to callbackUri(issuers, realm, method)),
            ),
        ).fold(
            onSuccess = { issued ->
                val separator = if ('?' in issued.redirectUri) '&' else '?'
                val state = issued.state?.let { "&state=" + it.encodeURLParameter() }.orEmpty()
                call.respondRedirect("${issued.redirectUri}$separator" + "code=" + issued.code.encodeURLParameter() + state)
            },
            onFailure = { error -> call.respondFailure(error, reporter) },
        )
    }

    suspend fun io.ktor.server.routing.RoutingContext.submitLogin(
        realm: String,
        method: String,
    ) {
        val form = call.receiveParameters()
        val state = form["state"].orEmpty()

        // We have no cookie session, so an ordinary CSRF token has nothing to be tied to.
        // We check where the request came from: we serve the form, so it must be posted back to
        // us. The parked request is left untouched — otherwise one forged POST from a foreign
        // page would be enough to throw a person out of their own sign-in.
        if (!call.sameOrigin(issuers, realm)) {
            call.respond(HttpStatusCode.Forbidden, OAuthError("invalid_request"))
            return
        }

        submit(
            SubmitLoginUseCase.Params(
                realm = realm,
                state = state,
                formParameters = form.entries().associate { it.key to it.value.first() },
            ),
        ).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is LoginOutcome.Success -> {
                        val issued = outcome.issued
                        val separator = if ('?' in issued.redirectUri) '&' else '?'
                        val clientState = issued.state?.let { "&state=" + it.encodeURLParameter() }.orEmpty()
                        call.respondRedirect(
                            "${issued.redirectUri}$separator" + "code=" + issued.code.encodeURLParameter() + clientState,
                        )
                    }

                    LoginOutcome.Wrong ->
                        call.respondLoginPage(
                            loginPath(realm, method),
                            state,
                            LoginPage.Problem.WRONG,
                            HttpStatusCode.Unauthorized,
                        )

                    LoginOutcome.Locked ->
                        call.respondLoginPage(
                            loginPath(realm, method),
                            state,
                            LoginPage.Problem.LOCKED,
                            HttpStatusCode.TooManyRequests,
                        )

                    LoginOutcome.Expired ->
                        call.respondLoginPage(
                            loginPath(realm, method),
                            state,
                            LoginPage.Problem.EXPIRED,
                            HttpStatusCode.BadRequest,
                        )
                }
            },
            onFailure = { error -> call.respondFailure(error, reporter) },
        )
    }

    suspend fun io.ktor.server.routing.RoutingContext.serveJwks(realm: String) {
        call.readableByAPage()

        // Deliberately shorter than the client's day-long cache: the window is set by the
        // client anyway (protocol §3).
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = JWKS_CACHE_SECONDS))

        getJwks(realm).fold(
            onSuccess = { call.respond<JsonObject>(it) },
            onFailure = { error ->
                if (error is UnknownRealm) {
                    call.respond(HttpStatusCode.NotFound, OAuthError("unknown_realm"))
                } else {
                    throw error
                }
            },
        )
    }

    suspend fun io.ktor.server.routing.RoutingContext.serveUserInfo(realm: String) {
        val token =
            call.request
                .header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                .orEmpty()

        val tenant = tenants.byRealm(realm)
        val claims = if (tenant == null || token.isBlank()) null else verifyToken(tenant.id, token)

        if (claims == null) {
            // The header from RFC 6750: clients read it as "refresh the token".
            call.response.header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
            call.respond(HttpStatusCode.Unauthorized, OAuthError("invalid_token"))
            return
        }

        // We return exactly what the token already carries: a `userinfo` that reaches into the
        // database would start to diverge from the token, and a client would see different things
        // depending on where it went.
        call.respond(JsonObject(claims.filterKeys { it in USERINFO_CLAIMS }))
    }

    routing {
        post<RealmResource.OpenIdConnect.Token> { issueTokens(it.parent.parent.realm) }
        post<OAuth2.Token> { issueTokens(it.parent.realm) }

        options<RealmResource.OpenIdConnect.Token> { call.answerPreflight() }
        options<OAuth2.Token> { call.answerPreflight() }

        /**
         * The start of a browser sign-in.
         *
         * The answer is a redirect carrying the code to the client's `redirect_uri`. Errors are
         * **not** redirected: at this step the address is not confirmed yet, and sending anything
         * to it would be exactly what checking the list protects against.
         */
        get<RealmResource.OpenIdConnect.Auth> { startAuthorization(it.parent.parent.realm) }
        get<OAuth2.Authorize> { startAuthorization(it.parent.realm) }

        /** The return from an external provider. */
        get<RealmResource.OpenIdConnect.Auth.Callback> { completeCallback(it.parent.parent.parent.realm, it.method) }
        get<OAuth2.Callback> { completeCallback(it.parent.realm, it.method) }

        post<RealmResource.OpenIdConnect.Auth.Login> { submitLogin(it.parent.parent.parent.realm, it.method) }
        post<OAuth2.Login> { submitLogin(it.parent.realm, it.method) }

        get<StylesheetResource> {
            // Served from its own URL with a long cache: otherwise seventy kilobytes would
            // travel with every sign-in attempt.
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondText(PICO_CSS, ContentType.Text.CSS)
        }

        get<RealmResource.OpenIdConnect.Certs> { serveJwks(it.parent.parent.realm) }
        get<OAuth2.Jwks> { serveJwks(it.parent.realm) }

        options<RealmResource.OpenIdConnect.Certs> { call.answerPreflight() }
        options<OAuth2.Jwks> { call.answerPreflight() }

        get<RealmResource.OpenIdConnect.UserInfo> { serveUserInfo(it.parent.parent.realm) }
        get<OAuth2.UserInfo> { serveUserInfo(it.parent.realm) }

        get<RealmResource.OpenIdConnect.Logout> { logout(it.parent.parent.realm, call.request.queryParameters) }
        get<OAuth2.Logout> { logout(it.parent.realm, call.request.queryParameters) }

        post<RealmResource.OpenIdConnect.Logout> { logout(it.parent.parent.realm, call.receiveParameters()) }
        post<OAuth2.Logout> { logout(it.parent.realm, call.receiveParameters()) }

        get<RealmResource.Discovery> { resource ->
            call.readableByAPage()
            val realm = resource.parent.realm

            // Every address below is built out of the issuer, so a document for a realm that does
            // not exist looks exactly like a document for one that does. Discovery is the first
            // request a client makes and the first thing anybody reads to check a configuration:
            // answering it for a realm nobody ever created means a mistyped name reads as a healthy
            // provider, and the search for the cause goes to the keys, the network, CORS — anywhere
            // but the realm. The lie used to surface only at `jwks`, the first address here that
            // asks storage anything.
            if (tenants.byRealm(realm) == null) {
                call.respond(HttpStatusCode.NotFound, OAuthError("unknown_realm"))
                return@get
            }

            val issuer = issuers.issuerFor(realm)

            call.respond(
                DiscoveryDocument(
                    issuer = issuer,
                    // The new URLs — the ones without the Keycloak legacy. Clients take them
                    // from here rather than from their own settings, so the move happens on their
                    // side by itself; the old routes keep being served for anything that hardcoded
                    // them.
                    authorizationEndpoint = "$issuer/oauth2/authorize",
                    tokenEndpoint = "$issuer/oauth2/token",
                    userInfoEndpoint = "$issuer/oauth2/userinfo",
                    endSessionEndpoint = "$issuer/oauth2/logout",
                    jwksUri = "$issuer/oauth2/jwks",
                    // From the one place that decides which scopes need no grant. A second copy of
                    // this list would be right until somebody added a fifth name to one of them.
                    // The scopes a client may actually hold are not advertised: they belong to a
                    // client, and a global list would be a promise made on somebody else's behalf.
                    scopesSupported = Scopes.PROTOCOL.sorted(),
                ),
            )
        }

        options<RealmResource.Discovery> { call.answerPreflight() }
    }
}

/**
 * Exchanging a code for tokens.
 *
 * Pulled out of the route because there are more steps here than in the others: check the code,
 * find the tenant, sign two tokens.
 */
private suspend fun io.ktor.server.routing.RoutingContext.exchangeCode(
    realm: String,
    form: io.ktor.http.Parameters,
    clientId: String,
    reporter: ErrorReporter,
    exchange: ExchangeCodeUseCase,
    tenants: TenantRepository,
    issueUserTokens: IssueUserTokensUseCase,
) {
    exchange(
        ExchangeCodeUseCase.Params(
            realm = realm,
            clientId = clientId,
            code = form["code"].orEmpty(),
            redirectUri = form["redirect_uri"].orEmpty(),
            codeVerifier = form["code_verifier"],
            resources = form.getAll("resource").orEmpty().toSet(),
        ),
    ).fold(
        onSuccess = { exchanged ->
            val tenant = tenants.byRealm(realm)
            if (tenant == null) {
                call.respond(HttpStatusCode.BadRequest, OAuthError("invalid_grant"))
                return@fold
            }
            val tokens =
                issueUserTokens(
                    tenant = tenant,
                    user = exchanged.user,
                    clientId = exchanged.clientId,
                    nonce = exchanged.nonce,
                    scope = exchanged.scope,
                    audience = exchanged.audience,
                    permissions = exchanged.permissions,
                )
            call.respond(
                TokenResponse(
                    accessToken = tokens.accessToken,
                    expiresIn = tokens.expiresInSeconds,
                    idToken = tokens.idToken,
                    refreshToken = exchanged.refreshToken,
                    scope = tokens.scope,
                ),
            )
        },
        onFailure = { error -> call.respondFailure(error, reporter, unexpectedIsGrant = true) },
    )
}

/**
 * Refreshing tokens. Rotating: the answer carries a **new** refresh token, the previous one is
 * already spent.
 */
private suspend fun io.ktor.server.routing.RoutingContext.refreshSession(
    realm: String,
    form: io.ktor.http.Parameters,
    clientId: String,
    reporter: ErrorReporter,
    refresh: RefreshTokensUseCase,
    tenants: TenantRepository,
    issueUserTokens: IssueUserTokensUseCase,
) {
    refresh(
        RefreshTokensUseCase.Params(
            realm = realm,
            clientId = clientId,
            refreshToken = form["refresh_token"].orEmpty(),
            resources = form.getAll("resource").orEmpty().toSet(),
        ),
    ).fold(
        onSuccess = { refreshed ->
            val tenant = tenants.byRealm(realm)
            if (tenant == null) {
                call.respond(HttpStatusCode.BadRequest, OAuthError("invalid_grant"))
                return@fold
            }
            val tokens =
                issueUserTokens(
                    tenant = tenant,
                    user = refreshed.user,
                    clientId = refreshed.clientId,
                    nonce = null,
                    scope = refreshed.scope,
                    audience = refreshed.audience,
                    permissions = refreshed.permissions,
                )
            call.respond(
                TokenResponse(
                    accessToken = tokens.accessToken,
                    expiresIn = tokens.expiresInSeconds,
                    idToken = tokens.idToken,
                    refreshToken = refreshed.refreshToken,
                    scope = tokens.scope,
                ),
            )
        },
        onFailure = { error -> call.respondFailure(error, reporter, unexpectedIsGrant = true) },
    )
}

/**
 * Which sign-in method to use when the client did not say.
 *
 * When the build has exactly **one** method there is nothing to ask about, and that is a necessity
 * rather than a convenience: `oauth2-proxy` does not send an `auth_method` parameter and cannot be
 * taught to. The internal build knows exactly one method, and used to run into a hardcoded
 * `google` that is not part of it.
 *
 * When there are several, the choice belongs to the client: guessing on its behalf means one day
 * taking a person somewhere they did not intend to go.
 */
private fun AuthMethodRegistry.defaultId(): String = ids().singleOrNull() ?: FALLBACK_AUTH_METHOD

/** The product build knows two methods, and Google is the one owners sign in with. */
private const val FALLBACK_AUTH_METHOD = "google"

/** The URL of the sign-in form stylesheet. Outside any realm: the page is the same for all. */
@Resource("/assets/pico.classless.min.css")
private class StylesheetResource

/**
 * The single answer to a failure in the OIDC contour.
 *
 * Every route used to write `(error as? OAuthRejection)?.error ?: "server_error"` itself, and that
 * "?:" hid the whole difference between "the client sent nonsense" and "our database is down". Now
 * [OAuthFailure] tells them apart, and what is ours gets reported.
 */
private suspend fun ApplicationCall.respondFailure(
    error: Throwable,
    reporter: ErrorReporter,
    unexpectedIsGrant: Boolean = false,
) {
    val failure = OAuthFailure.of(error)
    if (failure.report) reporter.report(error)

    // On the token endpoint the default protocol code is a different one: a failure there is
    // almost always about the grant that was presented.
    val code = if (failure.report || !unexpectedIsGrant) failure.error else "invalid_grant"
    respond(failure.status, OAuthError(code))
}

/**
 * Where the sign-in form posts the password. Still the old URL for now: it is served, and moving
 * the form to the new one is a one-line edit better done together with the rest than on its own.
 */
private fun loginPath(
    realm: String,
    methodId: String,
) = "/realms/$realm/protocol/openid-connect/auth/$methodId/login"

private suspend fun ApplicationCall.respondLoginPage(
    actionPath: String,
    state: String,
    problem: LoginPage.Problem? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    // A page carrying a one-time `state` must be cached neither by intermediaries nor by the
    // browser.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(LoginPage.html(actionPath, state, problem), ContentType.Text.Html, status)
}

/**
 * Whether the request came from our own page.
 *
 * The browser sets `Origin` on every form POST, and a page cannot forge it. A missing header counts
 * as foreign: browsers do send it, and a non-browser has no use for our form.
 */
private fun ApplicationCall.sameOrigin(
    issuers: IssuerResolver,
    realm: String,
): Boolean {
    val origin = request.header(HttpHeaders.Origin) ?: return false
    val expected = issuers.issuerFor(realm).substringBefore("/realms/")
    return origin == expected
}

/**
 * The return address from an external provider — **deliberately the old one**, inherited from
 * Keycloak.
 *
 * M-84 introduced our own URLs (`oauth2/callback/{method}`), and the temptation to move this one
 * along with them is understandable. But this address is registered **in the Google console**:
 * allowed redirect_uris are listed there by name, and Google rejects a request with an
 * unregistered one — meaning an owner's sign-in breaks not in our test but in their browser.
 *
 * The order of the move: first the new address is added to the Google console (the old one stays),
 * then this line changes. Not the other way round.
 */
private fun callbackUri(
    issuers: IssuerResolver,
    realm: String,
    methodId: String,
) = "${issuers.issuerFor(realm)}/protocol/openid-connect/auth/$methodId/callback"

/**
 * The name of the parameter that carries the return address to a sign-in method. It lives here
 * rather than in the Google module: shared code has to be able to tell the address to any
 * redirecting method.
 */
private const val CALLBACK_URI_PARAM = "shildik_callback_uri"

/** Who presented themselves at the token endpoint. */
private class ClientCredentials(
    val clientId: String,
    val clientSecret: String,
)

/**
 * A client is recognised **both from the body and from the** `Authorization: Basic` **header**.
 *
 * RFC 6749 §2.3.1 allows both, and the client picks, not us. `@auth/core` sends `Basic` — and the
 * very first live sign-in attempt ran into `invalid_client`, because the body had no `client_id`
 * at all. In the log it looked like a lookup for a client with an empty identifier.
 *
 * Values inside `Basic` are additionally URL-encoded — the same section requires it, and without
 * decoding, identifiers with special characters would break.
 */
private fun clientCredentials(
    call: ApplicationCall,
    form: io.ktor.http.Parameters,
): ClientCredentials {
    val fromBody = form["client_id"].orEmpty()
    if (fromBody.isNotBlank()) return ClientCredentials(fromBody, form["client_secret"].orEmpty())

    val header = call.request.header("Authorization").orEmpty()
    if (!header.startsWith("Basic ")) return ClientCredentials("", "")

    val decoded =
        runCatching { header.removePrefix("Basic ").trim().decodeBase64String() }.getOrNull()
            ?: return ClientCredentials("", "")

    val separator = decoded.indexOf(':')
    if (separator < 0) return ClientCredentials("", "")

    return ClientCredentials(
        clientId = decoded.substring(0, separator).decodeURLPart(),
        clientSecret = decoded.substring(separator + 1).decodeURLPart(),
    )
}

/** What `userinfo` returns. `sub` is required by the specification, the rest is the profile. */
private val USERINFO_CLAIMS = setOf("sub", "email", "email_verified", "name")

private const val JWKS_CACHE_SECONDS = 300
