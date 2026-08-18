package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.submitForm
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.resources.href
import io.ktor.resources.serialization.ResourcesFormat
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import ru.workinprogress.shildik.shared.RealmResource
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

private fun now(): Long = Clock.System.now().toEpochMilliseconds()

/** A minute before expiry: a token with seconds left on it is useless to whoever receives it. */
private const val REFRESH_MARGIN_MILLIS = 60_000L

/**
 * A service token via `client_credentials`, cached and refreshed before it expires.
 *
 * Multiplatform: nothing JVM-specific is left here — time comes from `kotlin.time.Clock` and
 * logging from Ktor's logger — so a service on Kotlin/Native fetches tokens with the same code a
 * JVM service does.
 */
@OptIn(ExperimentalAtomicApi::class)
class OidcAuthService(
    private val config: OidcConfig,
    // Supplying your own client is fine, but it **must** be able to parse JSON: the token
    // response is read through `ContentNegotiation`. The default client installs it; a client
    // passed in is the caller's business, and without it the request fails with
    // `NoTransformationFoundException`.
    private val httpClient: HttpClient = providerHttpClient(),
) {
    // The address is not assembled from strings: it is described in `:shared-oidc` by the same
    // type the provider's router declares it with. They can no longer drift apart.
    private val tokenUrl: String = config.url.trimEnd('/') + href(ResourcesFormat(), config.tokenResource())

    private val currentToken = AtomicReference<AuthToken?>(null)
    private val tokenRefreshMutex = Mutex()

    // Ktor's logger rather than slf4j, because it is multiplatform. On the JVM it is slf4j
    // underneath; on native it prints to stdout at the level from `KTOR_LOG_LEVEL`.
    private val logger = KtorSimpleLogger("OidcAuthService")

    fun getBearerTokens(): BearerTokens? {
        val token = currentToken.load()
        return token?.let { BearerTokens(it.accessToken, it.refreshToken) }
    }

    suspend fun requestNewTokens(): AuthToken =
        tokenRefreshMutex.withLock {
            val existingToken = currentToken.load()
            if (existingToken != null && existingToken.expirationTimeMillis > now() + REFRESH_MARGIN_MILLIS) {
                return existingToken
            }

            logger.debug("Requesting new token...")

            val tokenResponse =
                httpClient
                    .submitForm(
                        tokenUrl,
                        formParameters =
                            Parameters.build {
                                append("grant_type", "client_credentials")
                                append("client_id", config.clientId)
                                append("client_secret", config.secret)
                            },
                    ).body<TokenResponse>()

            val newToken =
                AuthToken(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken,
                    expirationTimeMillis = now() + tokenResponse.expiresIn * 1000,
                )

            currentToken.store(newToken)
            logger.debug("New token acquired. Expires in ${tokenResponse.expiresIn} seconds.")
            return newToken
        }
}

private fun OidcConfig.tokenResource() = RealmResource.OpenIdConnect.Token(RealmResource.OpenIdConnect(RealmResource(realm)))

/**
 * The default client. The engine is deliberately left unset: the application picks it — CIO on the
 * JVM, curl in native builds. Pass your own client as the second parameter if you need to.
 *
 * Logging is on for a reason. Without it the trip **for the token** is invisible: the consumer's
 * log shows requests to a neighbour and their 401s, but not whether a token was ever requested or
 * what came back. That once cost an investigation — a service was sending requests with no
 * `Authorization` header, and the log could not tell "never asked for a token" from "asked and
 * did not get one".
 *
 * The secret-bearing header is scrubbed: the `client_credentials` form body only reaches the log
 * at `ALL`, and `Authorization` is never shown at any level.
 */
private fun providerHttpClient() =
    HttpClient {
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }

        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }

        defaultRequest { contentType(Json) }
    }
