package io.github.youndie.shildik.core.di

import io.github.youndie.shildik.core.config.ShildikConfig
import io.github.youndie.shildik.core.port.NoopTransactionManager
import io.github.youndie.shildik.core.port.TransactionManager
import io.github.youndie.shildik.crypto.MasterKeyCipher
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * The domain graph. Configuration arrives from outside: `:core` does not know where it was taken
 * from — `application.conf`, the environment, or a test.
 *
 * `TransactionManager` defaults to [NoopTransactionManager]; a build with real storage overrides it
 * with the adapter's module.
 */
fun coreModule(config: ShildikConfig): Module =
    module {
        single { config }
        single { MasterKeyCipher(config.masterKeys) }
        single<TransactionManager> { NoopTransactionManager }
        singleOf(::IssuerResolver)
    }

/**
 * Decides the `iss` of the tokens we issue.
 *
 * `iss` is always the address we are served at, and never somebody else's (research §R10). It must
 * not be spoofed: `next-auth` and oauth2-proxy check `iss` against the issuer from discovery, and a
 * seamless migration comes from taking over Keycloak's address, not from forging the claim.
 *
 * A separate class since M0, because the point where the issuer is decided has to be a single one:
 * per-tenant resolution will arrive here in M1.
 */
class IssuerResolver(
    private val config: ShildikConfig,
) {
    fun issuerFor(realm: String): String = "${config.issuer}/realms/$realm"
}
