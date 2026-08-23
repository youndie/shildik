package ru.workinprogress.shildik.shared

import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The public OIDC surface.
 *
 * The shape of the addresses follows Keycloak deliberately: services and libraries that already
 * spoke to one could be pointed at this provider without touching their configuration. Describing
 * it as resources rather than assembling strings inside a router means the whole surface is
 * visible in one place.
 */
@Resource("/realms/{realm}")
class RealmResource(
    val realm: String,
) {
    @Resource("protocol/openid-connect")
    class OpenIdConnect(
        val parent: RealmResource,
    ) {
        @Resource("token")
        class Token(
            val parent: OpenIdConnect,
        )

        /** Where a browser sign-in begins. */
        @Resource("auth")
        class Auth(
            val parent: OpenIdConnect,
        ) {
            /** Where an external provider returns the person. */
            @Resource("{method}/callback")
            class Callback(
                val parent: Auth,
                val method: String,
            )

            /** Where the sign-in form posts a login and a password. */
            @Resource("{method}/login")
            class Login(
                val parent: Auth,
                val method: String,
            )
        }

        /** The profile of whoever holds the token. Required in discovery by some clients even if never called. */
        @Resource("userinfo")
        class UserInfo(
            val parent: OpenIdConnect,
        )

        /** Sign-out: the refresh token chain is revoked and the person is handed back to the application. */
        @Resource("logout")
        class Logout(
            val parent: OpenIdConnect,
        )

        @Resource("certs")
        class Certs(
            val parent: OpenIdConnect,
        )
    }

    @Resource(".well-known/openid-configuration")
    class Discovery(
        val parent: RealmResource,
    )
}

/**
 * The same surface under addresses of our own.
 *
 * [RealmResource] copies Keycloak: `protocol/openid-connect/...` and `certs` are its vocabulary,
 * kept so services already speaking to one could be pointed here without touching their
 * configuration. That job is done, and this is the shape a provider would have if it were not
 * imitating anything: `oauth2/token`, `oauth2/authorize`, `oauth2/jwks`.
 *
 * **Both live side by side, and the old one is not going away soon.** A client reads addresses
 * from the discovery document, so pointing discovery here moves everyone over without them
 * noticing; anything that hardcoded the old paths keeps working meanwhile.
 *
 * **The `/realms/{realm}` prefix stays**, and that is not an oversight. It is part of the issuer,
 * the issuer is in every token ever issued and in every consumer's configuration, and moving it
 * is a migration of its own — not a rename. Discovery stays where it is for the same reason: a
 * client derives its address from the issuer, not from a setting.
 */
@Resource("/realms/{realm}/oauth2")
class OAuth2(
    val realm: String,
) {
    @Resource("token")
    class Token(
        val parent: OAuth2,
    )

    /** `authorize`, not `auth`: the endpoint is named that way in RFC 6749 §3.1. */
    @Resource("authorize")
    class Authorize(
        val parent: OAuth2,
    )

    /** `jwks`, not `certs`: the document is a JWK Set — [RFC 7517](https://www.rfc-editor.org/rfc/rfc7517). */
    @Resource("jwks")
    class Jwks(
        val parent: OAuth2,
    )

    @Resource("userinfo")
    class UserInfo(
        val parent: OAuth2,
    )

    @Resource("logout")
    class Logout(
        val parent: OAuth2,
    )

    /** Where an external provider returns the person. */
    @Resource("callback/{method}")
    class Callback(
        val parent: OAuth2,
        val method: String,
    )

    /** Where the sign-in form posts a login and a password. */
    @Resource("login/{method}")
    class Login(
        val parent: OAuth2,
        val method: String,
    )
}

/**
 * The token response.
 *
 * Field names are snake_case per [RFC 6749](https://www.rfc-editor.org/rfc/rfc6749). `@SerialName`
 * is not optional here: a client reads `access_token`, not `accessToken`.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Browser sign-in only: `client_credentials` has no person behind it and nothing to assert. */
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

@Serializable
data class OAuthError(
    val error: String,
)

/**
 * The discovery document. It declares **only what is actually supported**: clients read this and
 * believe it.
 */
@Serializable
data class DiscoveryDocument(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("jwks_uri") val jwksUri: String,
    /**
     * Required even when never called: `@auth/core` fails discovery with
     * `TypeError: Authorization server did not provide a userinfo endpoint` when the field is
     * missing — found by reading the consumer, not the specification.
     */
    @SerialName("userinfo_endpoint") val userInfoEndpoint: String,
    @SerialName("end_session_endpoint") val endSessionEndpoint: String,
    @SerialName("response_types_supported") val responseTypesSupported: List<String> = listOf("code"),
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String> = listOf("authorization_code", "client_credentials", "refresh_token"),
    /**
     * `S256` only. Declaring `plain` is the same as allowing it: `oauth4webapi` picks from
     * whatever is written here, and `plain` protects nothing at all.
     */
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = listOf("S256"),
    /**
     * The default is a fallback, not the authority: the server passes what
     * `core`'s `Scopes.PROTOCOL` says, and that is the list to change. This module cannot read it —
     * `:core` depends on nothing here and adding the dependency for one constant would be worse
     * than a default that is kept in step.
     */
    @SerialName("scopes_supported")
    val scopesSupported: List<String> = listOf("openid", "profile", "email", "offline_access"),
    @SerialName("subject_types_supported") val subjectTypesSupported: List<String> = listOf("public"),
    @SerialName("id_token_signing_alg_values_supported")
    val idTokenSigningAlgValuesSupported: List<String> = listOf("RS256"),
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String> =
        listOf("client_secret_basic", "client_secret_post", "none"),
)
