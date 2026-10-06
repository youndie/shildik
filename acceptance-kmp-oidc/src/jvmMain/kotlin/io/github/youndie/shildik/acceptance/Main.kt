package io.github.youndie.shildik.acceptance

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.Jwt
import org.publicvalue.multiplatform.oidc.types.validateNonce
import org.publicvalue.multiplatform.oidc.types.validateState
import kotlin.system.exitProcess

/**
 * kalinjul/kotlin-multiplatform-oidc walks the whole app contour against a running shildik.
 *
 * Twice — once per kind of return address an app can have (issue #63):
 *
 * - **loopback on a port nobody registered** (`http://127.0.0.1:<port>/callback`), what a desktop app
 *   listens on (RFC 8252 §7.3, #64);
 * - **a private-use scheme** (`io.example.kmp:/callback`), what Android Custom Tabs and iOS
 *   `ASWebAuthenticationSession` hand back (RFC 8252 §7.1, #65).
 *
 * Each run: discovery → authorization request with PKCE S256 → the sign-in form, filled in and posted
 * → the code taken off the redirect → exchange → refresh → end session. Every step is the library's
 * own call except the form, which in an app is the person and the system browser.
 *
 * Usage: SHILDIK_PUBLIC_URL (must be the container's issuer — the form is refused from any other
 * origin), SHILDIK_MANAGEMENT_URL, SHILDIK_BOOTSTRAP_TOKEN. Expects a fresh server: it creates the
 * realm `kmp-oidc` and fails if it exists.
 */
fun main() {
    val public = env("SHILDIK_PUBLIC_URL", "http://localhost:8080").trimEnd('/')
    val management = env("SHILDIK_MANAGEMENT_URL", "http://localhost:9000").trimEnd('/')
    val token = env("SHILDIK_BOOTSTRAP_TOKEN", "bootstrap")

    val failures =
        runBlocking {
            HttpClient(CIO) { followRedirects = false }.use { http ->
                Bootstrap(http, management, token).run()
                listOf(LOOPBACK_REGISTERED.replace("127.0.0.1", "127.0.0.1:53124"), SCHEME).mapNotNull { redirect ->
                    // Both runs are reported even when the first fails: which of the two return
                    // addresses broke is the whole diagnosis.
                    try {
                        Walk(http, public, redirect).run()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "$redirect: ${e::class.simpleName}: ${e.message}"
                    }
                }
            }
        }

    if (failures.isEmpty()) {
        println("acceptance: kotlin-multiplatform-oidc signed in, refreshed and signed out on both return addresses")
    } else {
        failures.forEach { System.err.println("acceptance FAILED — $it") }
        exitProcess(1)
    }
}

private const val REALM = "kmp-oidc"
private const val CLIENT = "kmp-app"
private const val LOGIN = "kmp@example.test"
private const val PASSWORD = "kmp-password-1"
private const val LOOPBACK_REGISTERED = "http://127.0.0.1/callback"
private const val SCHEME = "io.example.kmp:/callback"

private fun env(
    name: String,
    default: String,
): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/** A tenant, a public app client with both return addresses, and a person with a password. */
private class Bootstrap(
    private val http: HttpClient,
    private val management: String,
    private val token: String,
) {
    suspend fun run() {
        admin("/admin/tenants", """{"realm":"$REALM"}""")
        admin(
            "/admin/tenants/$REALM/clients",
            """{"clientId":"$CLIENT","public":true,"redirectUris":["$LOOPBACK_REGISTERED","$SCHEME"]}""",
        )
        admin("/admin/tenants/$REALM/users", """{"id":"kmp-user","email":"$LOGIN"}""")
        val password =
            http.put("$management/admin/tenants/$REALM/users/kmp-user/password") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"password":"$PASSWORD"}""")
            }
        check(password.status.isSuccess()) { "setting the password: ${password.status} ${password.bodyAsText()}" }
    }

    private suspend fun admin(
        path: String,
        body: String,
    ) {
        val response =
            http.post("$management$path") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        check(response.status.isSuccess()) { "POST $path: ${response.status} ${response.bodyAsText()}" }
    }
}

private class Walk(
    private val http: HttpClient,
    private val public: String,
    private val redirect: String,
) {
    private val client =
        OpenIdConnectClient(discoveryUri = "$public/realms/$REALM/.well-known/openid-configuration") {
            clientId = CLIENT
            redirectUri = redirect
            postLogoutRedirectUri = redirect
            scope = "openid profile email offline_access"
        }

    suspend fun run() {
        client.discover()
        val request = client.createAuthorizationCodeRequest()

        val code = signIn(request)
        val tokens = client.exchangeToken(request, code)
        val idToken = checkNotNull(tokens.id_token) { "no id_token: the provider is declared as OIDC" }
        val firstRefresh =
            checkNotNull(tokens.refresh_token) { "no refresh_token although offline_access was asked for" }
        val claims = Jwt.parse(idToken).payload
        check(request.validateNonce(claims.nonce.orEmpty())) { "the id_token carries another nonce: ${claims.nonce}" }
        check(claims.aud.orEmpty().contains(CLIENT)) { "the id_token is not addressed to the client: ${claims.aud}" }

        val refreshed = client.refreshToken(firstRefresh)
        val secondRefresh = checkNotNull(refreshed.refresh_token) { "a refresh issued no new refresh token" }
        check(secondRefresh != firstRefresh) { "the refresh token was not rotated" }

        // What an app does on sign-out: open the end-session address in the system browser and
        // wait for the redirect back to itself. The library builds the address; the browser step is
        // ours.
        val endSession = client.createEndSessionRequest(refreshed.id_token ?: idToken)
        val back = http.get(endSession.url.toString())
        val location = back.headers[HttpHeaders.Location].orEmpty()
        check(location.startsWith(redirect)) {
            "end session did not return to the app: ${back.status}, Location '$location'"
        }

        // And the library's own back-channel call.
        val status = client.endSession(idToken)
        check(status.value in 200..399) { "endSession answered $status" }
    }

    /** The person and the system browser: open the address, fill in the form, catch the code. */
    private suspend fun signIn(request: AuthCodeRequest): String {
        val page = http.get(request.url.toString())
        val html = page.bodyAsText()
        check(
            page.status.isSuccess() && "<form" in html,
        ) { "the authorization request gave no sign-in form: ${page.status}" }

        val parked = attribute(html, "name=\"state\" value=\"") ?: error("the sign-in page carries no state")
        val action = attribute(html, "action=\"") ?: error("the sign-in form has no action")
        val target = if (action.startsWith("http")) action else public + action

        val posted =
            http.submitForm(
                url = target,
                formParameters =
                    parameters {
                        append("state", parked)
                        append("login", LOGIN)
                        append("password", PASSWORD)
                    },
            ) { header(HttpHeaders.Origin, public) }

        val location = posted.headers[HttpHeaders.Location].orEmpty()
        check(location.startsWith("$redirect?")) {
            "signing in did not return to the app: ${posted.status}, Location '$location'"
        }
        // Not `Url(location)`: a URL type normalises, and for a private-use scheme that is exactly
        // the rewrite #65 exists to rule out. The query is all that is read here.
        val query = Url("http://x/?" + location.substringAfter('?')).parameters
        val state = query["state"] ?: error("the redirect carries no state")
        check(request.validateState(state)) { "the state came back changed: $state" }
        return query["code"] ?: error("the redirect carries no code")
    }

    private fun attribute(
        html: String,
        prefix: String,
    ): String? {
        val start = html.indexOf(prefix).takeIf { it >= 0 } ?: return null
        val from = start + prefix.length
        return html.substring(from, html.indexOf('"', from)).replace("&amp;", "&")
    }
}
