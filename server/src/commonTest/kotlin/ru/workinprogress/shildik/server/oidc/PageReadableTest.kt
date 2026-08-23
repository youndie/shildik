package ru.workinprogress.shildik.server.oidc

import io.ktor.client.request.get
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The headers without which a single-page client cannot read an answer.
 *
 * Every browser client of this provider so far ran its half of the flow on a server, so a fetch
 * never happened in a browser and nothing needed to say who may read the result. A client that
 * exchanges the code in the page itself stops at the very first step without these, and the error
 * it shows names CORS rather than sign-in.
 *
 * **What this does not check** is that the three endpoints call it. That needs the whole routing
 * graph, and building one here would mean fakes for every port the provider has; the wiring is
 * checked instead against the running provider, by asking discovery for its headers — which is how
 * the absence was found in the first place.
 */
class PageReadableTest {
    @Test
    fun `an answer marked page readable may be read by any origin and without credentials`() =
        testApplication {
            routing {
                get("/document") {
                    call.readableByAPage()
                    call.respondText("{}")
                }
            }

            val response = client.get("/document")

            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
            // Absent on purpose, and the one line worth guarding: allowing credentials together
            // with any origin would let another page act as whoever is signed in here.
            assertEquals(null, response.headers[HttpHeaders.AccessControlAllowCredentials])
        }

    @Test
    fun `a preflight is answered rather than not found`() =
        testApplication {
            routing { options("/token") { call.answerPreflight() } }

            val response = client.options("/token")

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("GET, POST, OPTIONS", response.headers[HttpHeaders.AccessControlAllowMethods])
        }
}
