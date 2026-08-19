package ru.workinprogress.shildik.core

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import ru.workinprogress.shildik.core.config.ShildikConfig
import ru.workinprogress.shildik.core.di.IssuerResolver
import ru.workinprogress.shildik.core.di.coreModule
import ru.workinprogress.shildik.core.port.TransactionManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * With Koin a broken graph shows up only at runtime (research §R12), which makes "the graph builds"
 * a mandatory test rather than a formality. It also exercises `singleOf(::…)` on every target,
 * native included: the constructor DSL works without JVM reflection.
 */
class CoreModuleTest {
    private val config =
        ShildikConfig(
            issuer = "https://shildik.example",
            publicPort = 8080,
            managementPort = 9000,
            masterKeys = listOf("test-master-key"),
        )

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun `the graph builds and every dependency resolves`() {
        val koin = startKoin { modules(coreModule(config)) }.koin

        koin.get<ShildikConfig>()
        koin.get<TransactionManager>()
        koin.get<IssuerResolver>()
    }

    /**
     * `iss` is built from the instance's configuration and from nowhere else: it must not be
     * spoofed — a seamless migration comes from taking over the address, not from forging the claim
     * (research §R10).
     */
    @Test
    fun `the issuer is built from the instance address`() {
        val koin = startKoin { modules(coreModule(config)) }.koin

        assertEquals(
            "https://shildik.example/realms/main",
            koin.get<IssuerResolver>().issuerFor("main"),
        )
    }
}
