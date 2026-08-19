package ru.workinprogress.shildik.core.model

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * A tenant — what the outward URL calls a `realm` (research §R7).
 *
 * Inside the domain the term differs deliberately: in a B2B IdP this is a tenant, while `realm` is
 * another product's word that we keep in URLs only for compatibility.
 */
data class Tenant(
    val id: TenantId,
    val realm: String,
    /**
     * Whether to admit somebody we do not know yet.
     *
     * For the owners' contour, yes: the first sign-in by magic link is the registration. For the
     * internal contour, no: monitoring sits behind the provider there, and "confirmed their
     * identity with Google" must not mean "may look at our metrics". An external provider answers
     * "who is this", not "this one is allowed" (feature-closed-registration).
     *
     * Open by default — otherwise introducing the flag would have broken an existing contour
     * silently.
     */
    val registrationOpen: Boolean = true,
)

@JvmInline
value class TenantId(
    val value: String,
)

/**
 * A client is a program, not a person. The secret is stored **only** as a hash: `secretHash` is
 * what lies in the database, and it does not unfold back (api/endpoint-admin.md §3).
 */
data class Client(
    val tenantId: TenantId,
    val clientId: String,
    val secretHash: String,
    val roles: Set<String>,
    /**
     * A public client is one that has **no secret at all** (api/protocol-oidc-browser.md §3). Not
     * an "empty secret": an empty one would fit anybody. The protection rests on PKCE and the list
     * of redirect addresses.
     */
    val public: Boolean = false,
    /**
     * Where the code may be returned. Matched **exactly**, with no wildcards: a `*` in a
     * redirect_uri has historically been the main way to steal another client's code.
     */
    val redirectUris: Set<String> = emptySet(),
) {
    init {
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(!public || secretHash.isEmpty()) {
            "a public client cannot have a secret: it is known to everyone who opened the page"
        }
    }

    fun allowsRedirect(uri: String): Boolean = uri in redirectUris
}

/**
 * An authorization code: single-use and short-lived.
 *
 * Stored as a hash, like a client secret — in the database it is just as much a presented secret.
 * It lives in storage rather than in memory: a pod restarts between the redirect and the exchange,
 * and a lost code looks like "sign-in works every other time" (api/protocol-oidc-browser.md §4).
 */
data class AuthorizationCode(
    val tenantId: TenantId,
    val codeHash: String,
    val clientId: String,
    val userId: String,
    val redirectUri: String,
    /** PKCE: `S256` of the verifier. Blank means the code was issued without PKCE, which a public client may not do. */
    val codeChallenge: String,
    val scope: String,
    val nonce: String?,
    val expiresAt: Instant,
    val used: Boolean = false,
)

/**
 * A person, not a program.
 *
 * For imported people the `id` is **not issued by us**: it is the identifier from the previous
 * provider, and the same one travels into the token's `sub`. The reason is that a relying service
 * recognises a person by the pair `(sub, iss)` (feature-user-import §2): had we issued our own
 * identifier, an owner would have signed in to an empty account.
 *
 * There are no passwords here and there will be none: they live in their own storage,
 * `CredentialRepository`. A password field added "just in case" is a field somebody will fill in
 * one day.
 */
data class User(
    val tenantId: TenantId,
    val id: String,
    val email: String?,
    val name: String?,
    val emailVerified: Boolean,
    val enabled: Boolean,
    val identities: Set<ExternalIdentity>,
) {
    init {
        require(id.isNotBlank()) { "a user identifier must not be blank" }
    }
}

/**
 * A link to an external sign-in provider: it is how a person is recognised on the way back from
 * Google.
 *
 * `provider` is a short key (`google`) rather than an address: addresses change, and tying a
 * person's identity to them is the very mistake that made `(sub, iss)` something to untangle.
 */
data class ExternalIdentity(
    val provider: String,
    val subject: String,
) {
    init {
        require(provider.isNotBlank()) { "a provider must not be blank" }
        require(subject.isNotBlank()) { "an identifier at the provider must not be blank" }
    }
}

/**
 * An authorization request waiting for a person to return from an external provider.
 *
 * Between "left for Google" and "came back" there is time in which a pod may restart. Keeping this
 * in memory means losing sign-ins on every rollout, so it goes to storage, like the authorization
 * code.
 */
data class PendingAuthorization(
    val tenantId: TenantId,
    val state: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val clientState: String?,
    val nonce: String?,
    val codeChallenge: String,
    val methodId: String,
    val expiresAt: Instant,
)

/**
 * A refresh token.
 *
 * Stored as a hash and **rotated**: every exchange issues a new one and spends the presented one.
 * For a public client this is no luxury — it has no secret, and a leaked refresh token would
 * otherwise work for months.
 *
 * `family` ties together the whole chain grown from a single sign-in. If an already spent token is
 * presented, somebody saved it, and there is no honest explanation for that: **the whole family**
 * is revoked, not only the presented token. A user will survive signing in again; quietly
 * continuing to issue tokens to whoever presented something stolen is not survivable.
 */
data class RefreshToken(
    val tenantId: TenantId,
    val tokenHash: String,
    val family: String,
    val clientId: String,
    val userId: String,
    val scope: String,
    val expiresAt: Instant,
    val used: Boolean = false,
    /** When it was presented. Needed to tell a parallel refresh from a leak. */
    val usedAt: Instant? = null,
)

/**
 * The state of a signing key.
 *
 * `RETIRING` exists only for somebody else's JWKS cache: clients hold keys for a day, and a key
 * removed right after a rotation breaks verification silently (feature-signing-keys §3).
 */
enum class KeyState { ACTIVE, RETIRING, RETIRED }

data class SigningKeyRecord(
    val tenantId: TenantId,
    val kid: String,
    val privateKeyDer: ByteArray,
    val state: KeyState,
    val createdAt: Instant,
    val retiringSince: Instant?,
) {
    // A ByteArray in a data class breaks equals/hashCode — we compare by kid, which is the
    // identity anyway.
    override fun equals(other: Any?): Boolean = other is SigningKeyRecord && other.kid == kid && other.tenantId == tenantId

    override fun hashCode(): Int = 31 * tenantId.hashCode() + kid.hashCode()
}

/**
 * A counter of failed sign-in attempts.
 *
 * Needed by the password method and by it alone: with Google and the magic link there is nothing to
 * guess — what is presented there is another provider's signature, not a guessed string.
 *
 * The key is the pair "tenant and the login that was typed", not the user: non-existent addresses
 * get guessed too, and a difference in behaviour would make them visible.
 */
data class LoginAttempt(
    val tenantId: TenantId,
    val login: String,
    val failures: Int,
    val lockedUntil: Instant?,
) {
    fun locked(now: Instant): Boolean = lockedUntil != null && now < lockedUntil
}
