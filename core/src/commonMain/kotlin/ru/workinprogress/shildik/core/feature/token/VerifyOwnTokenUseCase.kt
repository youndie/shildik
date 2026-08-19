package ru.workinprogress.shildik.core.feature.token

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.core.feature.keys.ActiveSigningKey
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.crypto.Jws
import kotlin.time.Clock

/**
 * Verifying a token we issued ourselves.
 *
 * Both `userinfo` and sign-out need it: each has to recognise the bearer. What is checked is the
 * **signature and the expiry**, and nothing else — deciding "what this bearer may do" is the
 * caller's job.
 *
 * The key is picked by `kid` rather than taken as the current one: after a rotation there are
 * tokens in circulation signed with the previous key, and it is still in JWKS (research §Risk 2).
 */
class VerifyOwnTokenUseCase(
    private val activeKey: ActiveSigningKey,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        tenantId: TenantId,
        token: String,
    ): JsonObject? {
        val parsed = Jws.parse(token) ?: return null
        val key = activeKey.byKid(tenantId, parsed.kid ?: return null) ?: return null

        // A failure inside `verify` is still "the token is not ours", not a fault of ours. The JVM
        // throws on a signature of the wrong length (`Bad signature length: got 261 but was
        // expecting 256`), and letting that escape turned a forged token into a 500 and a report to
        // monitoring — while the client is owed a plain 401.
        if (!runCatching { key.verify(parsed.signingInput, parsed.signature) }.getOrDefault(false)) return null

        val exp = (parsed.claims["exp"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        if (exp <= clock.now().epochSeconds) return null

        return parsed.claims
    }
}
