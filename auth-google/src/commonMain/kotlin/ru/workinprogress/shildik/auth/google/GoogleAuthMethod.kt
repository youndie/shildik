package ru.workinprogress.shildik.auth.google

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.workinprogress.shildik.core.feature.auth.AuthRequest
import ru.workinprogress.shildik.core.feature.auth.AuthenticatedSubject
import ru.workinprogress.shildik.core.feature.auth.RedirectingAuthMethod

/**
 * Signing in through Google.
 *
 * **Why `userinfo` rather than parsing the `id_token`.** Parsing somebody else's `id_token` would
 * mean verifying its signature against Google's JWKS, caching those keys and following their
 * rotation — another JOSE validator in a project that keeps the surface of its own JOSE code
 * deliberately small. The token here was received by us directly from Google over TLS in a
 * server-to-server exchange, so a `userinfo` response over the same channel is exactly as
 * trustworthy.
 *
 * The identity is Google's `sub`, not the email: a person can change their email, and binding by
 * it would mean that changing an address creates a new user.
 */
class GoogleAuthMethod(
    private val clientId: String,
    private val clientSecret: String,
    engine: HttpClientEngine? = null,
    private val authorizationEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    private val userInfoEndpoint: String = "https://openidconnect.googleapis.com/v1/userinfo",
) : RedirectingAuthMethod {
    override val id = ID

    private val json = Json { ignoreUnknownKeys = true }

    private val http =
        if (engine == null) HttpClient { configure() } else HttpClient(engine) { configure() }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    override fun authorizationUrl(
        callbackUri: String,
        state: String,
    ): String =
        authorizationEndpoint +
            "?response_type=code" +
            "&client_id=" + clientId.encodeURLParameter() +
            "&redirect_uri=" + callbackUri.encodeURLParameter() +
            "&scope=" + "openid email profile".encodeURLParameter() +
            "&state=" + state.encodeURLParameter()

    override suspend fun authenticate(request: AuthRequest): AuthenticatedSubject? {
        // Google refused — the person pressed cancel or withheld consent. Not a server error.
        if (request["error"] != null) return null
        val code = request["code"] ?: return null
        val callbackUri = request[CALLBACK_URI_PARAM] ?: return null

        val tokenResponse =
            http.submitForm(
                url = tokenEndpoint,
                formParameters =
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("redirect_uri", callbackUri)
                    },
            )
        if (!tokenResponse.status.isSuccess()) return null

        val accessToken = tokenResponse.body<GoogleTokens>().accessToken

        val userInfo = http.get(userInfoEndpoint) { header("Authorization", "Bearer $accessToken") }
        if (!userInfo.status.isSuccess()) return null

        val profile = json.decodeFromString(GoogleProfile.serializer(), userInfo.bodyAsText())
        return AuthenticatedSubject(
            externalId = profile.sub,
            email = profile.email,
            name = profile.name,
            // Google does **not** always confirm an address, and says so honestly. Taking its
            // "no" for a "yes" means allowing a link to somebody else's account by their address.
            emailVerified = profile.emailVerified,
        )
    }

    companion object {
        const val ID = "google"

        /**
         * The redirect address arrives in the parameters because Google checks it when the code
         * is exchanged: it must be the one from the authorization request, or the exchange fails.
         */
        const val CALLBACK_URI_PARAM = "shildik_callback_uri"
    }
}

@Serializable
private data class GoogleTokens(
    @SerialName("access_token") val accessToken: String,
)

@Serializable
private data class GoogleProfile(
    val sub: String,
    val email: String? = null,
    val name: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean = false,
)
