package ru.workinprogress.oidc

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.Payload
import io.ktor.server.auth.jwt.JWTPrincipal
import kotlinx.serialization.json.JsonObject
import ru.workinprogress.auth.RoleBasedPrincipal

/**
 * The principal on the JVM, with the same surface a `jwks-rsa`-based build had.
 *
 * Two promises to consumers are kept here, and breaking either would break running services:
 *
 * * [RoleBasedPrincipal] — `withRole(...)` rests on it. Not implementing it would authenticate a
 *   caller successfully and then answer 403 to everyone whose route checks a role;
 * * `originalPrincipal` and `payload` — how services reach `email`, `name`, `picture` or the
 *   `(sub, iss)` pair. The token is **decoded without verification** here on purpose: verification
 *   already happened in common code, and `JWT.decode` is needed only for the shape consumers
 *   expect.
 */
actual class OidcPrincipal : RoleBasedPrincipal {
    val originalPrincipal: JWTPrincipal
    actual val azp: String
    actual val email: String?
    actual override val roles: Set<String>
    actual val subject: String?
    actual val claims: JsonObject

    internal actual constructor(verified: VerifiedToken) {
        originalPrincipal = JWTPrincipal(JWT.decode(verified.rawToken))
        // `azp` stays non-nullable: consumers read it without a null check. A token without
        // `azp` belongs to a person rather than a service, and an empty string here is more
        // honest than a crash out of nowhere.
        azp = verified.azp.orEmpty()
        email = verified.email
        roles = verified.roles
        subject = verified.subject
        claims = verified.claims
    }

    /**
     * Building a principal by hand — how a consumer's tests create one when substituting their own
     * authentication. This constructor exists for them.
     */
    constructor(
        originalPrincipal: JWTPrincipal,
        azp: String,
        email: String?,
        roles: Set<String>,
    ) {
        this.originalPrincipal = originalPrincipal
        this.azp = azp
        this.email = email
        this.roles = roles
        this.subject = originalPrincipal.payload.subject
        this.claims = JsonObject(emptyMap())
    }

    val payload: Payload get() = originalPrincipal.payload

    override fun toString(): String = "OidcPrincipal(azp=$azp, email=$email, roles=$roles)"
}
