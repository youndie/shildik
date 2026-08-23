package ru.workinprogress.shildik.core.feature.token

import ru.workinprogress.shildik.core.model.Client

/**
 * What a token permits, as opposed to where it may be spent.
 *
 * `aud` answers "which service is this for"; `scope` answers "what may be done there". A resource
 * server needs both, and everything built on OAuth asks the second question of `scope` — an MCP
 * server hands back `WWW-Authenticate: Bearer …, scope="tasks:read"` and refuses a token without
 * it, however correct its audience. Our `realm_access.roles` does not answer it: roles are ours,
 * read by our own services, and a third party has no reason to know the word.
 *
 * The rule is the same as for audiences, deliberately: **a client may only ask for a scope it was
 * given**, asking for nothing yields everything it was given, and a client given nothing gets a
 * token with no `scope` claim — the token this provider issued before any of this existed.
 *
 * ## Protocol scopes are not permissions
 *
 * `openid`, `profile`, `email` and `offline_access` say which tokens and which claims to produce.
 * They are instructions to us, not authority over somebody else's service, and every browser client
 * has been sending them since before clients had a scope list at all. Requiring a grant for them
 * would refuse every sign-in on this contour the moment this shipped, so they pass through
 * untouched — and they stay out of the claim, which describes what the bearer may do.
 */
object Scopes {
    /**
     * Scopes that shape our own answer rather than granting anything.
     *
     * Kept as a closed list rather than a prefix rule: a rule broad enough to cover these would
     * eventually be right about most names and wrong about one, and the one would be a permission
     * let through unasked.
     */
    val PROTOCOL = setOf("openid", "profile", "email", "offline_access")

    /**
     * What ends up in the claim. Refuses a scope the client was not granted.
     *
     * The request is split rather than filtered: a protocol scope is neither granted nor refused,
     * and dropping it silently from a check is the same as deciding it is always allowed — which it
     * is, and that decision belongs in one place with its reason.
     */
    fun resolve(
        client: Client,
        requested: Set<String>,
    ): Set<String> {
        val asked = requested - PROTOCOL
        asked.forEach { scope ->
            if (!client.allowsScope(scope)) throw UnknownScope(scope)
        }
        return asked.ifEmpty { client.scopes }
    }

    /**
     * The claim, or null when there is nothing to put in it.
     *
     * Space-delimited and sorted: RFC 6749 §3.3 defines the format, and the order carries no
     * meaning, so a stable one makes two tokens for the same grant compare equal.
     */
    fun claim(scopes: Set<String>): String? = scopes.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(" ")

    /** Splits a `scope` parameter. Any run of spaces, because senders disagree about how many. */
    fun parse(value: String?): Set<String> =
        value
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
}

/**
 * A client asked for a scope it was not granted.
 *
 * `invalid_scope` is the error RFC 6749 §5.2 names for this, and — like `invalid_target` next door
 * — deliberately not `invalid_client`: the client is who it says it is, and whoever configured it
 * needs to see that a permission is missing from the list rather than that the secret is wrong.
 */
class UnknownScope(
    val scope: String,
) : Exception("invalid_scope")
