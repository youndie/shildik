package ru.workinprogress.shildik.storage.sqlx4k

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.workinprogress.shildik.core.port.AuthorizationCodeRepository
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.CredentialRepository
import ru.workinprogress.shildik.core.port.KeyRepository
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import ru.workinprogress.shildik.core.port.PendingAuthorizationRepository
import ru.workinprogress.shildik.core.port.RefreshTokenRepository
import ru.workinprogress.shildik.core.port.StorageHealth
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.port.TransactionManager
import ru.workinprogress.shildik.core.port.UserRepository

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
