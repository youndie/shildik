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
    @SerialName("scopes_supported")
    val scopesSupported: List<String> = listOf("openid", "profile", "email", "offline_access"),
    @SerialName("subject_types_supported") val subjectTypesSupported: List<String> = listOf("public"),
    @SerialName("id_token_signing_alg_values_supported")
    val idTokenSigningAlgValuesSupported: List<String> = listOf("RS256"),
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String> =
        listOf("client_secret_basic", "client_secret_post", "none"),
)
