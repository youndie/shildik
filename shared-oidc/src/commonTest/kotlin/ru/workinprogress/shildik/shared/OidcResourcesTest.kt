package ru.workinprogress.shildik.shared

import io.ktor.resources.href
import io.ktor.resources.serialization.ResourcesFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The addresses are a contract, so they are pinned here rather than read off the router.
 *
 * A resource class renders to a path through serialization, which means a rename of a property or
 * a reshuffle of nesting changes the URL without changing a single string in sight. These tests
 * are what turns such a change from silent into loud.
 */
class OidcResourcesTest {
    // Reified: `href` needs the serializer of the concrete resource class, and `Any` has none —
    // passing the value through a non-reified parameter loses exactly that.
    private inline fun <reified T : Any> path(resource: T): String = href(ResourcesFormat(), resource)

    private val realm = OAuth2("main")

    @Test
    fun `our own addresses`() {
        assertEquals("/realms/main/oauth2/token", path(OAuth2.Token(realm)))
        assertEquals("/realms/main/oauth2/authorize", path(OAuth2.Authorize(realm)))
        assertEquals("/realms/main/oauth2/jwks", path(OAuth2.Jwks(realm)))
        assertEquals("/realms/main/oauth2/userinfo", path(OAuth2.UserInfo(realm)))
        assertEquals("/realms/main/oauth2/logout", path(OAuth2.Logout(realm)))
        assertEquals("/realms/main/oauth2/login/password", path(OAuth2.Login(realm, "password")))
        assertEquals("/realms/main/oauth2/callback/google", path(OAuth2.Callback(realm, "google")))
    }

    /**
     * The Keycloak-shaped addresses stay exactly as they are. Anything that hardcoded them —
     * a proxy configuration, a chart, somebody else's client — keeps working while discovery
     * moves the rest over.
     */
    @Test
    fun `the inherited addresses do not move`() {
        val oidc = RealmResource.OpenIdConnect(RealmResource("main"))

        assertEquals("/realms/main/protocol/openid-connect/token", path(RealmResource.OpenIdConnect.Token(oidc)))
        assertEquals("/realms/main/protocol/openid-connect/auth", path(RealmResource.OpenIdConnect.Auth(oidc)))
        assertEquals("/realms/main/protocol/openid-connect/certs", path(RealmResource.OpenIdConnect.Certs(oidc)))
    }

    /**
     * Discovery is derived by clients from the issuer, so its address is pinned by the issuer and
     * not by us: `{issuer}/.well-known/openid-configuration`.
     */
    @Test
    fun `discovery stays under the issuer`() {
        assertEquals("/realms/main/.well-known/openid-configuration", path(RealmResource.Discovery(RealmResource("main"))))
    }
}
