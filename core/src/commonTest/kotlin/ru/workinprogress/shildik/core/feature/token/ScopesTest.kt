package ru.workinprogress.shildik.core.feature.token

import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.TenantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a token permits where it is spent.
 *
 * The same three edges as the audience next door — what a client may ask for, what happens when it
 * asks for nothing, what happens when it asks for something it was not given — plus the one edge
 * that has no counterpart there: **protocol scopes**. Every browser client on this contour has been
 * sending `openid profile email` since long before clients had a scope list, so a rule that treated
 * those as permissions would have refused every sign-in the moment it shipped. That case is held
 * here on purpose, not left to be discovered by a person who cannot get in.
 */
class ScopesTest {
    private fun client(vararg scopes: String) =
        Client(
            tenantId = TenantId("t"),
            clientId = "some-client",
            secretHash = "hash",
            roles = emptySet(),
            scopes = scopes.toSet(),
        )

    @Test
    fun `a client gets what it asked for when it was granted it`() {
        val resolved = Scopes.resolve(client("tasks:read", "tasks:write"), setOf("tasks:read"))

        assertEquals(setOf("tasks:read"), resolved)
    }

    @Test
    fun `asking for nothing means everything this client may do`() {
        val resolved = Scopes.resolve(client("tasks:read", "tasks:write"), emptySet())

        assertEquals(setOf("tasks:read", "tasks:write"), resolved)
    }

    @Test
    fun `a client with no permissions gets a token with no scope`() {
        assertEquals(emptySet(), Scopes.resolve(client(), emptySet()))
        assertNull(Scopes.claim(emptySet()), "a token that used to have no scope must still have none")
    }

    @Test
    fun `a scope the client was not granted is refused`() {
        // Not dropped quietly: a caller that asked to write and got a token that may only read
        // would find out at the resource server, where the refusal names us rather than its own
        // configuration.
        val refused =
            assertFailsWith<UnknownScope> {
                Scopes.resolve(client("tasks:read"), setOf("tasks:write"))
            }

        assertEquals("tasks:write", refused.scope)
    }

    @Test
    fun `protocol scopes need no grant and do not become permissions`() {
        // A client with nothing granted asks the way every browser client already does.
        val resolved = Scopes.resolve(client(), setOf("openid", "profile", "email", "offline_access"))

        assertEquals(emptySet(), resolved, "they say which tokens to issue, not what the bearer may do")
        assertNull(Scopes.claim(resolved))
    }

    @Test
    fun `a protocol scope alongside a granted one leaves only the permission`() {
        val resolved = Scopes.resolve(client("tasks:read"), setOf("openid", "tasks:read"))

        assertEquals(setOf("tasks:read"), resolved)
    }

    @Test
    fun `the claim is space delimited and ordered`() {
        assertEquals("tasks:read", Scopes.claim(setOf("tasks:read")))
        assertEquals("tasks:read tasks:write", Scopes.claim(setOf("tasks:write", "tasks:read")))
    }

    @Test
    fun `a parameter is split on any run of spaces`() {
        assertEquals(setOf("openid", "tasks:read"), Scopes.parse("openid  tasks:read "))
        assertEquals(emptySet(), Scopes.parse(null))
        assertEquals(emptySet(), Scopes.parse("   "))
    }
}
