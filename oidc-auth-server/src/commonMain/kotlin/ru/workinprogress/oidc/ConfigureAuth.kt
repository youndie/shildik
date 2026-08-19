package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.resources.href
import io.ktor.resources.serialization.ResourcesFormat
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import kotlinx.serialization.json.JsonObject
import ru.workinprogress.shildik.shared.RealmResource

const val JWT_AUTH_OIDC = "jwt-auth-oidc"

data class AuthData(
    val roles: Set<String>,
    val email: String?,
    val azp: String?,
)

/**
 * Whoever presented the token.
 *
 * `expect` rather than a plain class, for compatibility: on the JVM it also carries
 * `originalPrincipal` and `payload` for services written against Ktor's JWT plugin, and it
 * implements `RoleBasedPrincipal` so `withRole(...)` keeps working. Neither exists on native and
 * neither can — both are JVM libraries. What is declared here is what everyone uses.
 */
expect class OidcPrincipal internal constructor(
    verified: VerifiedToken,
) {
    val azp: String
    val email: String?
    val roles: Set<String>
    val subject: String?
    val claims: JsonObject
}

/**
 * Token verification for a Ktor service.
 *
 * **It verifies with its own code rather than `jwks-rsa`.** The reason is practical: `jwks-rsa`
 * and `ktor-server-auth-jwt` exist only on the JVM, so while verification rested on them, no
 * service could move to Kotlin/Native. Parsing and signatures come from `crypto`.
 *
 * What is checked and what is not is described in [TokenVerifier]; briefly: the signature and the
 * lifetime, and `iss` deliberately not.
 *
 * There can be two key sources — the second appears for the duration of a provider migration
 * ([OidcConfig.additionalUrl]) and goes away with it.
 *
 * @param engine the HTTP client engine; tests need it to substitute JWKS. In production the
 *   platform default is used — CIO on the JVM, curl in native builds.
 */
fun Application.configureAuth(
    config: OidcConfig,
    engine: HttpClientEngine? = null,
    validate: (AuthData) -> Boolean,
) {
    val logger = log
    val client = if (engine == null) HttpClient() else HttpClient(engine)
    // The client lives as long as the application and closes with it. Without that, every server
    // a test starts would leave a connection pool behind.
    monitor.subscribe(ApplicationStopped) { client.close() }

    val verifier = TokenVerifier(config.keys(client, log = logger::info))

    install(Authentication) {
        bearer(JWT_AUTH_OIDC) {
            realm = config.realm

            authenticate { credential ->
                val verified = verifier.verify(credential.token) ?: return@authenticate null

                // No email and no subject in the message text. A log line becomes a **template**
                // in an aggregator, and a template is treated as safe to show: a value
                // interpolated into the text is not caught by name-based redaction. That is how
                // an owner's email address once ended up in a template dictionary, with every
                // subject spawning a template of its own.
                //
                // There is nowhere to put the values as fields here: this is common code, MDC is
                // JVM-only, and Ktor's logger takes no structured fields. So the only lever left
                // is not writing values into the text. `azp` stays: it identifies the client, it
                // has a handful of values, and it produces a handful of templates.
                logger.info("authorized: ${verified.azp}")

                if (validate(AuthData(verified.roles, verified.email, verified.azp))) {
                    OidcPrincipal(verified)
                } else {
                    null
                }
            }
        }
    }
}

/**
 * The inherited JWKS address, kept as the fallback when a provider has no discovery document.
 *
 * It is built from `shared-oidc` by the same type the provider's router declares, so the two
 * cannot drift apart. Where the address comes from in the normal case is [EndpointAddresses].
 */
internal fun certsUrl(
    base: String,
    realm: String,
): String =
    base.trimEnd('/') +
        href(
            ResourcesFormat(),
            RealmResource.OpenIdConnect.Certs(RealmResource.OpenIdConnect(RealmResource(realm))),
        )

internal fun OidcConfig.keys(
    client: HttpClient,
    log: (String) -> Unit,
): KeySource {
    val primary = JwksSource(client, EndpointAddresses(client, url, realm)::jwksUrl)
    if (additionalUrl.isBlank()) return primary

    val additional = additionalRealm.ifBlank { realm }
    log("Tokens from two providers will be accepted: $url and $additionalUrl (realm $additional)")
    return MultiSourceKeys(
        listOf(primary, JwksSource(client, EndpointAddresses(client, additionalUrl, additional)::jwksUrl)),
    )
}
