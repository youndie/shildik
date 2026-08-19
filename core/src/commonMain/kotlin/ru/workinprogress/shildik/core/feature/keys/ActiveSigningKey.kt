package ru.workinprogress.shildik.core.feature.keys

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.workinprogress.shildik.core.model.KeyState
import ru.workinprogress.shildik.core.model.SigningKeyRecord
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.KeyRepository
import ru.workinprogress.shildik.crypto.MasterKeyCipher
import ru.workinprogress.shildik.crypto.SigningKey
import kotlin.time.Clock

/**
 * The key we sign with right now.
 *
 * It caches the parsed key in memory: decrypting and parsing DER for every issued token is
 * needless work on a hot path. A rotation drops the cache.
 *
 * The first key is created **lazily, on first use**: an empty instance must not demand a manual
 * "generate a key" step, otherwise the very first request after a deployment gets a 500 and that
 * looks like a fault rather than an unfinished setup.
 */
class ActiveSigningKey(
    private val keys: KeyRepository,
    private val cipher: MasterKeyCipher,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<TenantId, SigningKey>()

    suspend fun forTenant(tenantId: TenantId): SigningKey =
        mutex.withLock {
            cache.getOrPut(tenantId) { loadOrCreate(tenantId) }
        }

    /** Drop the cache after a rotation — otherwise we keep signing with a retired key. */
    suspend fun invalidate(tenantId: TenantId) =
        mutex.withLock {
            cache.remove(tenantId)
            Unit
        }

    /**
     * A key by `kid` — retiring ones included.
     *
     * The retiring ones are the point: after a rotation, tokens signed with the previous key are
     * still in circulation, and it is still in JWKS (research §Risk 2). Verifying them with the
     * current key only would mean rejecting tokens we issued ourselves five minutes ago.
     */
    suspend fun byKid(
        tenantId: TenantId,
        kid: String,
    ): SigningKey? =
        keys
            .published(tenantId)
            .firstOrNull { it.kid == kid }
            ?.let { SigningKey.fromPrivateDer(it.kid, cipher.decrypt(it.privateKeyDer)) }

    private suspend fun loadOrCreate(tenantId: TenantId): SigningKey {
        keys.active(tenantId)?.let { return SigningKey.fromPrivateDer(it.kid, cipher.decrypt(it.privateKeyDer)) }

        val kid = newKid()
        val generated = SigningKey.generate(kid)
        keys.save(
            SigningKeyRecord(
                tenantId = tenantId,
                kid = kid,
                privateKeyDer = cipher.encrypt(generated.encodePrivate()),
                state = KeyState.ACTIVE,
                createdAt = clock.now(),
                retiringSince = null,
            ),
        )
        return generated
    }

    private fun newKid(): String = "k-" + clock.now().toEpochMilliseconds().toString(36)
}
