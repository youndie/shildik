package ru.workinprogress.shildik.core.feature.token

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.TenantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Which resource a token is addressed to.
 *
 * This is the rule that decides whether a token issued for one service will be accepted by another,
 * so all three of its edges are held here: what a client may name, what happens when it names
 * nothing, and what happens when it names something it was not given.
 *
 * The absence of the claim used to be the only behaviour there was — every token this provider
 * issued was one every service could be shown, and a resource server that checked the audience
 * refused all of them. Which is why "empty means no claim" is asserted rather than assumed: it is
 * what keeps clients configured before this change working exactly as they did.
 */
class AudiencesTest {
    private fun client(vararg audiences: String) =
        Client(
            tenantId = TenantId("t"),
            clientId = "some-client",
            secretHash = "hash",
            roles = emptySet(),
            audiences = audiences.toSet(),
        )

    @Test
    fun `a client gets what it asked for when it was granted it`() {
        val resolved = Audiences.resolve(client("https://a.example/", "https://b.example/mcp"), setOf("https://b.example/mcp"))

        assertEquals(setOf("https://b.example/mcp"), resolved)
    }

    @Test
    fun `asking for nothing means everything this client is for`() {
        val resolved = Audiences.resolve(client("https://a.example/", "https://b.example/mcp"), emptySet())

        assertEquals(setOf("https://a.example/", "https://b.example/mcp"), resolved)
    }

    @Test
    fun `a client with no resources gets a token with no audience`() {
        assertEquals(emptySet(), Audiences.resolve(client(), emptySet()))
        assertNull(Audiences.claim(emptySet()), "a token that used to have no aud must still have none")
    }

    @Test
    fun `a resource the client was not granted is refused`() {
        // Not "ignored" and not "silently dropped": either would hand back a token addressed
        // somewhere other than where the caller asked, and the caller would spend it there.
        val refused =
            assertFailsWith<UnknownResource> {
                Audiences.resolve(client("https://a.example/"), setOf("https://somebody-elses.example/mcp"))
            }

        assertEquals("https://somebody-elses.example/mcp", refused.resource)
    }

    @Test
    fun `one resource is a string and several are an array`() {
        assertEquals(JsonPrimitive("https://a.example/"), Audiences.claim(setOf("https://a.example/")))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("https://a.example/"), JsonPrimitive("https://b.example/"))),
            Audiences.claim(setOf("https://b.example/", "https://a.example/")),
        )
    }
}
