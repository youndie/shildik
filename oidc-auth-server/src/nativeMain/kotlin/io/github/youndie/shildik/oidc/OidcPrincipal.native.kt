package io.github.youndie.shildik.oidc

import kotlinx.serialization.json.JsonObject

/**
 * The principal in a native build — only what was verified.
 *
 * `payload` and `originalPrincipal` are absent here: they are types from `java-jwt` and
 * `ktor-server-auth-jwt`, that is, from the JVM. Everything they carried is in [claims], as the
 * same JSON that arrived in the token.
 *
 * `RoleBasedPrincipal` is absent too, because Ktor's authentication plugin — and therefore the
 * role plugin built on it — is JVM-only. A native service checks roles itself, from [roles].
 */
public actual class OidcPrincipal internal actual constructor(
    verified: VerifiedToken,
) {
    public actual val azp: String = verified.azp.orEmpty()
    public actual val email: String? = verified.email
    public actual val roles: Set<String> = verified.roles
    public actual val subject: String? = verified.subject
    public actual val claims: JsonObject = verified.claims

    override fun toString(): String = "OidcPrincipal(azp=$azp, email=$email, roles=$roles)"
}
