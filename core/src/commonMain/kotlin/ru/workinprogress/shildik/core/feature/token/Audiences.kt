package ru.workinprogress.shildik.core.feature.token

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.core.model.Client

/**
 * Which resource a token is addressed to (RFC 8707).
 *
 * A token without an audience is a token every service may be shown. That is survivable while every
 * service belongs to the same people, and it stops being survivable the moment one of them is
 * something a program talks to on somebody's behalf: a token taken from an agent would open the
 * screens of a person as well, and neither side could tell it was not meant for them. The claim is
 * what lets a resource server refuse a token that was issued for somebody else.
 *
 * **A client may only name a resource it was given.** Otherwise asking for an audience would be a
 * way to mint a token addressed to a service the client has no business with — the reverse of what
 * the claim is for.
 *
 * **Naming nothing is not an error.** A request with no `resource` gets everything the client is
 * entitled to, and a client entitled to nothing gets a token with no `aud` — which is exactly the
 * token this provider issued before any of this existed. That is what keeps every client that
 * already works working.
 */
object Audiences {
    /** The audience of a token, or empty when this client has none. Refuses a resource not granted. */
    fun resolve(
        client: Client,
        requested: Set<String>,
    ): Set<String> {
        requested.forEach { resource ->
            if (!client.allowsAudience(resource)) throw UnknownResource(resource)
        }
        return requested.ifEmpty { client.audiences }
    }

    /**
     * The claim, or null when there is nothing to put in it.
     *
     * One resource is written as a string and several as an array, which is what RFC 7519 allows
     * and what every reader of a token already handles. A single-element array is legal too and is
     * not used: it is the form libraries most often get wrong, and there is nothing to gain by
     * finding out which ones.
     */
    fun claim(audiences: Set<String>): JsonElement? =
        when (audiences.size) {
            0 -> null
            1 -> JsonPrimitive(audiences.first())
            else -> JsonArray(audiences.sorted().map(::JsonPrimitive))
        }
}

/**
 * A client asked for a resource it was not granted.
 *
 * `invalid_target` is the error RFC 8707 §2 names for exactly this, and it is deliberately not
 * `invalid_client`: the client is who it says it is, and telling it so is what lets whoever
 * configured it see that the resource is missing from the list rather than that the secret is wrong.
 */
class UnknownResource(
    val resource: String,
) : Exception("invalid_target")
