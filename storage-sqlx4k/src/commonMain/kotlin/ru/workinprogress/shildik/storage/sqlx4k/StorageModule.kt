package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
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
 * Assembling the storage on sqlx4k — the same set of ports the previous adapter offered.
 *
 * The signatures match on purpose: swapping the adapter at the assembly point has to be a
 * one-line change, or the two storages cannot be compared with one and the same test suite.
 * (research-native §7.1).
 */
fun sqlx4kStorageModule(
    jdbcUrl: String,
    user: String,
    password: String,
    migrationsPath: String? = null,
): Module =
    module {
        single<Driver> {
            postgres(jdbcUrl, user, password).also { db ->
                // Migrations are the storage's business, as before: a service must not start on
                // a schema it does not know.
                migrationsPath?.let { path -> migrate(db, path) }
            }
            // The pool closes with the container — otherwise connections outlive the server. The
            // previous adapter had no such worry: it took a connection per transaction and gave
            // it back, so there was nothing to close. Here the pool keeps them open, and an
            // unclosed server carries a dozen away with it. In production there is one server and
            // it lives as long as the pod, so this only showed up in tests: forty servers in one
            // JVM exhausted Postgres `max_connections`, and the **whole** suite fell over on
            // `too many clients`.
        } onClose { driver -> driver?.let { db -> runBlocking { db.close() } } }
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
