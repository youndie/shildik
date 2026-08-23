package ru.workinprogress.shildik.server.oidc

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Three endpoints a browser client reads **with its own code** rather than by navigating to them.
 *
 * Everything else in this contour is reached by leaving one page and arriving at another, and a
 * navigation needs nothing from us. Discovery, the key set and the token endpoint are different:
 * a single-page client fetches them, and a browser hands a fetched response to the page only when
 * the response says it may. Without these headers the flow stops at the first step and the error
 * the developer sees names CORS rather than anything about signing in.
 *
 * Until now every browser client of this provider ran its half of the flow on a server — next-auth
 * behind a Next.js application — so the fetches happened outside a browser and the question never
 * came up. A client that runs the exchange in the page itself is what makes this necessary.
 *
 * ## Why any origin, and why that is not a hole
 *
 * CORS does not protect this server; it decides which page may **read** an answer. What these three
 * answers contain is a public document, a public key set, and a token issued to whoever presented a
 * valid code with the verifier that matches it. None of them is authority this server grants on the
 * strength of the browser's ambient state:
 *
 * * credentials are **not** allowed — no cookie is sent and none is honoured, so nothing that
 *   depends on somebody already being signed in can be reached through here;
 * * a public client's protection is PKCE and an exact `redirect_uri`, and a page that has neither
 *   the code nor the verifier gains nothing from being able to read a refusal.
 *
 * Which is also why this is applied to three endpoints by name instead of to the whole contour. The
 * sign-in form and the authorization endpoint stay unreadable to other origins — not because that
 * would be an exploit, but because a rule that is right for most addresses and wrong for one is the
 * kind that gets noticed after the one.
 */
internal fun ApplicationCall.readableByAPage() {
    response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    response.header(HttpHeaders.AccessControlAllowHeaders, HttpHeaders.ContentType)
    response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
    response.header(HttpHeaders.AccessControlMaxAge, PREFLIGHT_CACHE_SECONDS.toString())
}

/**
 * The answer to a preflight.
 *
 * A page's token request is form-encoded, which browsers send without asking first, so this is not
 * on the path the current client takes. It is here because the next client will send something that
 * does ask — a `POST` with a JSON body, or a header of its own — and a preflight answered with 404
 * fails as "the endpoint does not exist" rather than as "this origin may not".
 */
internal suspend fun ApplicationCall.answerPreflight() {
    readableByAPage()
    respond(HttpStatusCode.NoContent)
}

private const val PREFLIGHT_CACHE_SECONDS = 3600
