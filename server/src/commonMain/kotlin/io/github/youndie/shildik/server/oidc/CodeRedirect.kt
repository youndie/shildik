package io.github.youndie.shildik.server.oidc

import io.ktor.http.encodeURLParameter

/**
 * The address a code goes back to: the registered `redirect_uri` with `code` and `state` appended.
 *
 * One function for the three routes that end in a code — a sign-in confirmed on the spot, the form,
 * and the callback from another provider — because each of them used to build this string by hand,
 * and three copies of a redirect are three places for one of them to start re-encoding it.
 *
 * Built by concatenation rather than through a URL builder, on purpose: the address has already
 * matched the client's list byte for byte, and a builder normalises. For `https://…` that is
 * harmless; for an app's private-use scheme (`io.example.app:/callback`, RFC 8252 §7.1) a builder
 * that does not know the scheme may add a `//` or drop the path, and the app then never receives
 * the code.
 */
internal fun codeRedirect(
    redirectUri: String,
    code: String,
    state: String?,
): String {
    val separator = if ('?' in redirectUri) '&' else '?'
    val stateParameter = state?.let { "&state=" + it.encodeURLParameter() }.orEmpty()
    return "$redirectUri${separator}code=${code.encodeURLParameter()}$stateParameter"
}
