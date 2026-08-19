package ru.workinprogress.shildik.server.oidc

import io.ktor.http.HttpStatusCode
import ru.workinprogress.shildik.core.feature.admin.NotFound
import ru.workinprogress.shildik.core.feature.browser.OAuthRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BDD: shildik/BACKLOG.md M-52.
 *
 * What is checked is the distinction that used to hide inside a single `?:`: a protocol refusal
 * and a fault of ours looked the same to the client, and nobody ever learned about the second.
 */
class OAuthFailureTest {
    @Test
    fun `a protocol refusal is not an incident`() {
        val failure = OAuthFailure.of(OAuthRejection("invalid_client", "unknown client"))

        assertEquals("invalid_client", failure.error)
        assertEquals(HttpStatusCode.BadRequest, failure.status)
        assertFalse(failure.report, "there is nobody to report a provider working normally to")
    }

    @Test
    fun `an unknown realm is not our fault either`() {
        val failure = OAuthFailure.of(NotFound("tenant 'none'"))

        assertEquals("invalid_request", failure.error)
        assertEquals(HttpStatusCode.BadRequest, failure.status)
        assertFalse(failure.report)
    }

    @Test
    fun `a database that went down is an incident and a five hundred`() {
        // Blaming our own fault on the client with a 400 hides it both from them and from us.
        val failure = OAuthFailure.of(IllegalStateException("connection refused"))

        assertEquals("server_error", failure.error)
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertTrue(failure.report)
    }
}
