package ru.workinprogress.shildik.storage.sqlx4k.sqlite

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import ru.workinprogress.shildik.core.model.AuthorizationCode
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.KeyState
import ru.workinprogress.shildik.core.model.LoginAttempt
import ru.workinprogress.shildik.core.model.RefreshToken
import ru.workinprogress.shildik.core.model.SigningKeyRecord
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kAuthorizationCodeRepository
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kClientRepository
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kKeyRepository
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kLoginAttemptRepository
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kRefreshTokenRepository
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kTenantRepository
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kTransactionManager
import ru.workinprogress.shildik.storage.sqlx4k.Sqlx4kUserRepository
import ru.workinprogress.shildik.storage.sqlx4k.migrateUnlocked
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The repositories, against a real SQLite.
 *
 * These are the first tests the storage has, and they exist because the second adapter is where a
 * dialect actually differs: the SQL is shared with Postgres, so what has to be checked is not that
 * a query is right but that **SQLite answers it the same way** — a boolean read back as a boolean,
 * a blob as the same bytes, an upsert that updates instead of failing, a missing number that stays
 * missing. Anything that behaves differently here would behave differently in production, and
 * silently: every one of these values passes through the driver, not through our code.
 */
class SqliteStorageTest {
    private val database = TestDatabase.open("storage")
    private val db = database.db

    private val tenants = Sqlx4kTenantRepository(db)
    private val clients = Sqlx4kClientRepository(db)
    private val users = Sqlx4kUserRepository(db)
    private val keys = Sqlx4kKeyRepository(db)
    private val codes = Sqlx4kAuthorizationCodeRepository(db)
    private val refreshTokens = Sqlx4kRefreshTokenRepository(db)
    private val attempts = Sqlx4kLoginAttemptRepository(db)
    private val transactions = Sqlx4kTransactionManager(db)

    @AfterTest
    fun close() {
        database.close()
    }

    @Test
    fun `applying the migrations again finds nothing to do`() =
        runTest {
            // The bookkeeping table is the migrator's, and a second start-up is the ordinary case:
            // a pod restarts far more often than a schema changes.
            migrateUnlocked(db, requireNotNull(env("SHILDIK_TEST_MIGRATIONS")))

            tenants.create(tenant())
            assertEquals("main", tenants.byRealm("main")?.realm)
        }

    @Test
    fun `booleans survive the round trip`() =
        runTest {
            // SQLite has no boolean type: `true` in a statement is the integer 1 coming back. If
            // that did not read as a boolean, every flag in the provider — a public client, a
            // disabled user, a closed realm — would come back wrong side up.
            tenants.create(tenant(registrationOpen = false))
            clients.upsert(publicClient())
            users.upsert(user(emailVerified = true, enabled = false))

            assertFalse(tenants.byRealm("main")!!.registrationOpen)
            assertTrue(clients.find(TENANT, "web")!!.public)
            assertTrue(users.find(TENANT, "u1")!!.emailVerified)
            assertFalse(users.find(TENANT, "u1")!!.enabled)
        }

    @Test
    fun `a signing key comes back byte for byte`() =
        runTest {
            // The column is `bytea` on Postgres and `blob` here, and the key is a DER-encoded
            // private key: a driver that handed it back as text would break signing, not storage.
            tenants.create(tenant())
            val der = byteArrayOf(0, 1, 2, -1, 127, -128)
            keys.save(key(der))

            assertContentEquals(der, keys.active(TENANT)!!.privateKeyDer)
        }

    @Test
    fun `an upsert updates rather than fails`() =
        runTest {
            // `on conflict … do update set … excluded.…` is the one piece of syntax this schema
            // leans on that a database could refuse. SQLite has had it since 3.24; if the bundled
            // one were older, saving a client twice would fail on the primary key.
            tenants.create(tenant())
            clients.upsert(confidentialClient(roles = setOf("a")))
            clients.upsert(confidentialClient(roles = setOf("b", "c")))

            val stored = clients.find(TENANT, "api")!!
            assertEquals(setOf("b", "c"), stored.roles)
            assertEquals(1, clients.list(TENANT).count { it.clientId == "api" })
        }

    @Test
    fun `a number that was never written stays absent`() =
        runTest {
            // Three nullable columns carry a moment in time: `used_at`, `locked_until`,
            // `retiring_since`. A driver that turned NULL into 0 would date every one of them to
            // 1970 — a lock that has expired, a key retired long ago.
            tenants.create(tenant())
            refreshTokens.save(refreshToken())
            attempts.save(LoginAttempt(TENANT, "someone", failures = 1, lockedUntil = null))
            keys.save(key(byteArrayOf(1)))

            assertNull(refreshTokens.find(TENANT, "hash")!!.usedAt)
            assertNull(attempts.find(TENANT, "someone")!!.lockedUntil)
            assertNull(keys.active(TENANT)!!.retiringSince)
        }

    @Test
    fun `marking a code used says whether this call was the one that did it`() =
        runTest {
            // The answer is the number of rows the statement changed, and it is the whole defence
            // against replaying an authorization code: the second exchange has to be refused.
            tenants.create(tenant())
            codes.save(authorizationCode())

            assertTrue(codes.markUsed(TENANT, "code-hash"))
            assertFalse(codes.markUsed(TENANT, "code-hash"))
        }

    @Test
    fun `a transaction that fails leaves nothing behind`() =
        runTest {
            assertFails {
                transactions.withTransaction {
                    tenants.create(tenant())
                    error("something went wrong after the write")
                }
            }

            assertNull(tenants.byRealm("main"))
        }

    @Test
    fun `a row may outlive the tenant it points at`() =
        runTest {
            // **This states what is true, not what would be nice.** SQLite leaves foreign keys off
            // per connection, and there is no way to turn them on through this driver: it accepts
            // only the four URL parameters SQLite defines for filenames, and the pool opens
            // connections nobody here can reach with a `PRAGMA`. So the schema's `REFERENCES` are
            // a description of the shape, and the database enforces none of them.
            //
            // The test is here in both directions. It fails if a driver ever starts enforcing
            // them — which would be good news needing a second look, not a surprise in production
            // — and it keeps the fact visible to whoever reads the schema and assumes otherwise.
            // What actually keeps the rows consistent is the code: see the test below.
            clients.upsert(confidentialClient(tenantId = TenantId("ghost")))

            assertEquals(1, clients.list(TenantId("ghost")).size)
        }

    @Test
    fun `deleting a client takes its rows with it`() =
        runTest {
            // The integrity the database is not enforcing, enforced where it actually lives:
            // `delete` clears the child tables before the client. On Postgres the cascade would
            // catch a miss here; on SQLite nothing would, and orphaned roles would grant a client
            // that no longer exists whatever they say.
            tenants.create(tenant())
            clients.upsert(confidentialClient(roles = setOf("a", "b")))
            clients.delete(TENANT, "api")

            assertNull(clients.find(TENANT, "api"))
            assertEquals(emptyList(), clients.list(TENANT))
        }

    @Test
    fun `the database is the file it was asked for`() {
        // The pragma travels as a query parameter, and a second parameter next to it was kept by
        // the JVM driver **inside the file name** — the database appeared as `local.db?mode=rwc`,
        // an installation's data in a file nobody would think to back up. This is the check that
        // the URL is still a URL and not part of the path.
        val directory = requireNotNull(env("SHILDIK_TEST_TMP"))
        assertTrue(SystemFileSystem.metadataOrNull(Path("$directory/storage-0.db")) != null)
        assertTrue(
            SystemFileSystem.list(Path(directory)).none { '?' in it.name },
            "a query parameter ended up in a file name",
        )
    }

    private fun tenant(registrationOpen: Boolean = true) = Tenant(TENANT, "main", registrationOpen)

    private fun publicClient() =
        Client(
            tenantId = TENANT,
            clientId = "web",
            secretHash = "",
            roles = emptySet(),
            public = true,
            redirectUris = setOf("https://example.test/callback"),
        )

    private fun confidentialClient(
        tenantId: TenantId = TENANT,
        roles: Set<String> = emptySet(),
    ) = Client(tenantId = tenantId, clientId = "api", secretHash = "hash", roles = roles)

    private fun user(
        emailVerified: Boolean,
        enabled: Boolean,
    ) = User(
        tenantId = TENANT,
        id = "u1",
        email = "someone@example.test",
        name = "Someone",
        emailVerified = emailVerified,
        enabled = enabled,
        identities = setOf(ExternalIdentity("google", "sub-1")),
    )

    private fun key(der: ByteArray) =
        SigningKeyRecord(
            tenantId = TENANT,
            kid = "k1",
            privateKeyDer = der,
            state = KeyState.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            retiringSince = null,
        )

    private fun refreshToken() =
        RefreshToken(
            tenantId = TENANT,
            tokenHash = "hash",
            family = "family",
            clientId = "api",
            userId = "u1",
            scope = "openid",
            expiresAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

    private fun authorizationCode() =
        AuthorizationCode(
            tenantId = TENANT,
            codeHash = "code-hash",
            clientId = "api",
            userId = "u1",
            redirectUri = "https://example.test/callback",
            codeChallenge = "challenge",
            scope = "openid",
            nonce = null,
            expiresAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

    private companion object {
        private val TENANT = TenantId("t1")
    }
}
