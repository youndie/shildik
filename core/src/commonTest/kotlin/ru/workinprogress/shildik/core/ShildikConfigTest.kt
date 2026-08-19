package ru.workinprogress.shildik.core

import ru.workinprogress.shildik.core.config.ShildikConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The configuration is the one place where the service is allowed to fail at start-up. These tests
 * hold exactly that behaviour: starting quietly with an incomplete configuration is not allowed.
 */
class ShildikConfigTest {
    private fun config(
        issuer: String = "https://shildik.example",
        publicPort: Int = 8080,
        managementPort: Int = 9000,
        masterKeys: List<String> = listOf("test-master-key"),
    ) = ShildikConfig(issuer, publicPort, managementPort, masterKeys)

    @Test
    fun `a valid configuration builds`() {
        config()
    }

    @Test
    fun `a blank issuer brings the start down`() {
        assertFailsWith<IllegalArgumentException> { config(issuer = "") }
    }

    @Test
    fun `a blank master key brings the start down`() {
        assertFailsWith<IllegalArgumentException> { config(masterKeys = listOf("")) }
    }

    @Test
    fun `no master keys at all brings the start down`() {
        assertFailsWith<IllegalArgumentException> { config(masterKeys = emptyList()) }
    }

    @Test
    fun `the management port cannot equal the public one`() {
        assertFailsWith<IllegalArgumentException> { config(publicPort = 8080, managementPort = 8080) }
    }
}
