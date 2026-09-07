package io.github.youndie.shildik.storage.sqlx4k

import org.koin.core.module.Module
import org.koin.dsl.module
import io.github.youndie.shildik.core.port.AuthorizationCodeRepository
import io.github.youndie.shildik.core.port.ClientRepository
import io.github.youndie.shildik.core.port.CredentialRepository
import io.github.youndie.shildik.core.port.KeyRepository
import io.github.youndie.shildik.core.port.LoginAttemptRepository
import io.github.youndie.shildik.core.port.PendingAuthorizationRepository
import io.github.youndie.shildik.core.port.RefreshTokenRepository
import io.github.youndie.shildik.core.port.StorageHealth
import io.github.youndie.shildik.core.port.TenantRepository
import io.github.youndie.shildik.core.port.TransactionManager
import io.github.youndie.shildik.core.port.UserRepository

/**
 * The ports, with no driver among them.
 *
 * Split out so a second storage can reuse them: every repository above is written against
 * sqlx4k's `Driver` and the SQL both databases understand, so what a SQLite build needs to bring
 * of its own is the driver and the schema — not eleven more lines naming the same classes. Two
 * copies of this list would agree until somebody added a twelfth port to one of them.
 */
fun sqlx4kPorts(): Module =
    module {
        single<StorageHealth> { Sqlx4kStorageHealth(get()) }
        single<TenantRepository> { Sqlx4kTenantRepository(get()) }
        single<ClientRepository> { Sqlx4kClientRepository(get()) }
        single<UserRepository> { Sqlx4kUserRepository(get()) }
        single<CredentialRepository> { Sqlx4kCredentialRepository(get()) }
        single<LoginAttemptRepository> { Sqlx4kLoginAttemptRepository(get()) }
        single<AuthorizationCodeRepository> { Sqlx4kAuthorizationCodeRepository(get()) }
        single<RefreshTokenRepository> { Sqlx4kRefreshTokenRepository(get()) }
        single<PendingAuthorizationRepository> { Sqlx4kPendingAuthorizationRepository(get()) }
        single<KeyRepository> { Sqlx4kKeyRepository(get()) }
        single<TransactionManager> { Sqlx4kTransactionManager(get()) }
    }
