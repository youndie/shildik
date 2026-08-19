package ru.workinprogress.oidc

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.workinprogress.shildik.crypto.VerificationKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Where a key for a `kid` comes from. A type of its own so that searching sources can be tested. */
internal interface KeySource {
    suspend fun key(kid: String): VerificationKey?
}

/**
 * The keys of one provider: fetched in a batch and kept in memory.
 *
 * The day-long lifetime matches what `jwks-rsa` does with `cached(10, 24, TimeUnit.HOURS)`, and
 * keeping it matters: a signing key changes rarely, while fetching JWKS per request builds
 * somebody else's availability into your own hot path.
 *
 * **A miss is not cached, and not free either.** An unknown `kid` is either a key rotation (worth
 * fetching) or a junk token (must not fetch, or an outside request turns into a request to the
 * provider). The compromise: refetch, but no more often than [minRefetch]. `jwks-rsa` has no such
 * protection by default.
 */
internal class JwksSource(
    private val client: HttpClient,
    // A function rather than a string: the address is asked of the provider (see
    // `EndpointAddresses`), and asking has to happen at use, not at construction — a service
    // starts before its provider is necessarily up.
    private val jwksUrl: suspend () -> String,
    private val ttl: Duration = 24.hours,
    private val minRefetch: Duration = 10.seconds,
    private val now: () -> Instant = { Clock.System.now() },
) : KeySource {
    private val mutex = Mutex()
    private var keys: Map<String, VerificationKey> = emptyMap()
    private var loadedAt: Instant? = null

    override suspend fun key(kid: String): VerificationKey? {
        fresh()[kid]?.let { return it }

        return mutex.withLock {
            // Again under the lock: while waiting, the key may have arrived with somebody else's
            // refetch. `fresh` rather than `keys`, because an expired cache would look like a hit
            // here and the day-long TTL would quietly become "until restart".
            fresh()[kid]?.let { return@withLock it }

            val last = loadedAt
            if (last != null && now() - last < minRefetch) return@withLock null

            load()
            keys[kid]
        }
    }

    private suspend fun fresh(): Map<String, VerificationKey> {
        val last = loadedAt ?: return emptyMap()
        return if (now() - last > ttl) emptyMap() else keys
    }

    /**
     * An unreachable provider is a miss, not an error.
     *
     * A seamless provider migration rests on this: while the second source is configured but
     * dead, sign-in has to keep working through the first. `jwks-rsa` gets the same behaviour by
     * accident — its `NetworkException` extends `SigningKeyNotFoundException` — here it is
     * deliberate.
     *
     * A failure does **not** count as a refetch: `loadedAt` does not move, so the next request
     * tries again. Otherwise a provider that just came back would wait out the window. Live keys
     * are already in the cache, so the cost of that retry is only paid for unknown `kid`s.
     */
    private suspend fun load() {
        val body = runCatching { client.get(jwksUrl()).bodyAsText() }.getOrNull() ?: return
        val loaded = VerificationKey.fromJwks(body)
        if (loaded.isEmpty()) return

        keys = loaded.mapNotNull { key -> key.kid?.let { it to key } }.toMap()
        loadedAt = now()
    }
}

/**
 * Keys from several sources at once.
 *
 * Needed while an identity provider is being replaced: with some tokens signed by the old one and
 * some by the new, a service has to accept both.
 *
 * **Why not simply try each source per request.** A miss is not cached, so a plain search would
 * mean fetching the first source's JWKS for **every** token from the second. Instead the source
 * that produced a key for a `kid` is remembered: the search happens once per `kid`, and after that
 * requests go straight to the right source.
 *
 * A route is reconsidered when its source stops serving the key — which is how a rotation that
 * moves a `kid` from one source to another is survived.
 */
internal class MultiSourceKeys(
    private val sources: List<KeySource>,
) : KeySource {
    init {
        require(sources.isNotEmpty()) { "at least one key source is required" }
    }

    private val mutex = Mutex()
    private var routes: Map<String, KeySource> = emptyMap()

    override suspend fun key(kid: String): VerificationKey? {
        routes[kid]?.let { known ->
            known.key(kid)?.let { return it }
            mutex.withLock { routes = routes - kid }
        }

        for (source in sources) {
            val key = source.key(kid) ?: continue
            mutex.withLock { routes = routes + (kid to source) }
            return key
        }

        return null
    }
}
