package ru.workinprogress.shildik.server.oidc

import io.ktor.http.HttpStatusCode
import ru.workinprogress.shildik.core.feature.admin.NotFound
import ru.workinprogress.shildik.core.feature.browser.OAuthRejection
import ru.workinprogress.shildik.core.feature.token.InvalidClient

/**
 * How to answer a failure in the OIDC contour — and what to report while doing so.
 *
 * The distinction is not cosmetic. A protocol refusal ("unknown client", "expired code") is normal
 * work: this is how a provider is supposed to answer, and there is nobody to report it to and no
 * reason. An unreachable database or a broken signing key, on the other hand, is a fault of ours —
 * and before this change it looked to the client **exactly the same**: `server_error` with status
 * 400 and silence in monitoring.
 */
internal data class OAuthFailure(
    val error: String,
    val status: HttpStatusCode,
    /** Whether to report the incident: expected protocol refusals do not count. */
    val report: Boolean,
) {
    companion object {
        fun of(error: Throwable): OAuthFailure =
            when (error) {
                // A refusal foreseen by the protocol: 400 to the client with its own code.
                is OAuthRejection -> OAuthFailure(error.error, HttpStatusCode.BadRequest, report = false)

                // An unknown realm or client means the request is no good, not that we broke.
                is NotFound -> OAuthFailure("invalid_request", HttpStatusCode.BadRequest, report = false)

                // The client was not recognised: no such client, a public one asking for a
                // service token, a secret that did not match. All of it is a provider working
                // normally, nothing to report. Outwards all three get the same answer: telling
                // them apart would let somebody enumerate clients (protocol §2).
                is InvalidClient -> OAuthFailure("invalid_client", HttpStatusCode.BadRequest, report = false)

                // Everything else is ours. We answer **500**, not 400: blaming our own fault on
                // the client hides it both from them and from us.
                else -> OAuthFailure("server_error", HttpStatusCode.InternalServerError, report = true)
            }
    }
}
