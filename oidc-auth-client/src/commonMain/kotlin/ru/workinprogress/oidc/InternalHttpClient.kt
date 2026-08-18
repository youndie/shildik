package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.resources.Resources
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * A client for calling a neighbouring service, carrying a token that refreshes itself.
 *
 * Built around [OidcAuthService]: `loadTokens` and `refreshTokens` are that service.
 *
 * Two things here look like mistakes and are not:
 *
 * * `endpoint` goes into `host` whole, port and path prefix included (`orders-api:8080/internal`).
 *   Ktor accepts it, existing deployments are configured that way, and "fixing" it would break
 *   every internal call at once;
 * * `URLProtocol.HTTP` — these calls stay inside a cluster network, without TLS.
 *
 * @param engine the HTTP engine; by default the one the application already has — CIO on the JVM,
 *   curl in native builds.
 * @param logLevel **NONE** by default rather than `ALL`. At `ALL` the plugin prints the whole
 *   request and response — URL, query parameters and body — and every such line becomes its own
 *   template in a log aggregator. Over a day of near-zero traffic that produced more than three
 *   hundred templates, with customer data travelling inside the response bodies. What happened on
 *   a call is better answered by the server-side span on the receiving end. The previous behaviour
 *   is one argument away at the call site.
 */
fun provideClient(
    authService: OidcAuthService,
    config: OidcConfig,
    endpoint: String,
    logLevel: LogLevel = LogLevel.NONE,
    engine: HttpClientEngine? = null,
): HttpClient {
    val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        install(Resources)
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                },
            )
        }
        defaultRequest {
            contentType(ContentType.Application.Json)

            url {
                protocol = URLProtocol.HTTP
                host = endpoint
            }
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = logLevel
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }

        install(Auth) {
            bearer {
                realm = config.realm

                loadTokens { authService.getBearerTokens() }
                refreshTokens {
                    val token = authService.requestNewTokens()
                    BearerTokens(token.accessToken, token.refreshToken)
                }
            }
        }
    }

    return if (engine == null) HttpClient(configure) else HttpClient(engine, configure)
}
