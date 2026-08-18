package ru.workinprogress.oidc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.workinprogress.shildik.crypto.Jws
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A token whose signature matched and whose lifetime has not run out. The existence of this object
 * is the assertion: parsed-but-unverified claims never leave the verifier.
 */
class VerifiedToken internal constructor(
    val rawToken: String,
    val claims: JsonObject,
    val azp: String?,
    val email: String?,
    val subject: String?,
    val roles: Set<String>,
)

/**
 * Verifying somebody else's token: signature, lifetime, nothing beyond that.
 *
 * **`iss` is deliberately not checked.** Checking it would reject the second provider's tokens
 * precisely for being the second provider's — and the whole possibility of a seamless migration
 * rests on a service accepting both. Two key sources live side by side until the old provider is
 * gone; comparing issuers would defeat that.
 *
 * `aud` is not checked either: Keycloak-shaped providers put `account` there, and no meaningful
 * check comes out of it.
 */
internal class TokenVerifier(
    private val keys: KeySource,
    private val skew: Duration = 60.seconds,
    private val now: () -> Instant = { Clock.System.now() },
) {
    suspend fun verify(token: String): VerifiedToken? {
        val parsed = Jws.parse(token) ?: return null

        // The algorithm is a filter, not a choice: verification uses an RSA key regardless, and a
        // token with `alg: none` or HS256 would not get through anyway. Rejecting it here is
        // cheaper than explaining later why a signature "did not match".
        if (parsed.alg != "RS256") return null

        val kid = parsed.kid ?: return null
        val key = keys.key(kid) ?: return null
        if (!key.verify(parsed.signingInput, parsed.signature)) return null

        val claims = parsed.claims
        val moment = now()

        // **A token without `exp` is rejected**, which is stricter than the usual JVM stack:
        // `java-jwt` checks the lifetime only when there is one, so a token without an expiry
        // lived forever. Providers always set `exp`, so the difference shows up exactly where one
        // would want it to.
        val expiresAt = claims.seconds("exp") ?: return null
        if (moment > expiresAt + skew) return null
        claims.seconds("nbf")?.let { if (moment < it - skew) return null }

        return VerifiedToken(
            rawToken = token,
            claims = claims,
            azp = claims.text("azp"),
            email = claims.text("email"),
            subject = claims.text("sub"),
            roles = claims.realmRoles(),
        )
    }
}

/** Roles live in `realm_access.roles` — where Keycloak-shaped providers put them. */
internal fun JsonObject.realmRoles(): Set<String> =
    runCatching {
        this["realm_access"]
            ?.jsonObject
            ?.get("roles")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
    }.getOrNull() ?: emptySet()

/** `JsonNull` means the value is absent, not the string `"null"` it otherwise pretends to be. */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * Times are seconds since the epoch ([RFC 7519 §4.1.4](https://www.rfc-editor.org/rfc/rfc7519)).
 * A non-number means the claim is absent rather than zero.
 */
private fun JsonObject.seconds(name: String): Instant? =
    (this[name] as? JsonPrimitive)?.content?.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
