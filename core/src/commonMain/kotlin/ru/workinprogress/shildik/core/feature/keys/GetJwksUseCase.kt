package ru.workinprogress.shildik.core.feature.keys

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import ru.workinprogress.shildik.core.port.KeyRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.usecase.UseCase
import ru.workinprogress.shildik.core.usecase.suspendRunCatching
import ru.workinprogress.shildik.crypto.MasterKeyCipher
import ru.workinprogress.shildik.crypto.SigningKey

class UnknownRealm(
    realm: String,
) : Exception("Unknown realm: $realm")

/**
 * A tenant's JWKS.
 *
 * We hand out **every** key in `ACTIVE` and `RETIRING`, not only the current one: clients cache
 * JWKS for 24 hours, and a key that vanishes right after a rotation breaks verification silently
 * (feature-signing-keys §3).
 */
class GetJwksUseCase(
    private val tenants: TenantRepository,
    private val keys: KeyRepository,
    private val activeKey: ActiveSigningKey,
    private val cipher: MasterKeyCipher,
) : UseCase<String, JsonObject> {
    override suspend fun invoke(params: String): Result<JsonObject> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params) ?: throw UnknownRealm(params)

            // The same lazy path as when issuing a token: an empty instance returns a valid JWKS
            // with one key rather than an empty list, from which a client would conclude we are
            // broken.
            activeKey.forTenant(tenant.id)

            val jwks =
                keys.published(tenant.id).map { record ->
                    SigningKey.fromPrivateDer(record.kid, cipher.decrypt(record.privateKeyDer)).publicJwk()
                }

            JsonObject(mapOf("keys" to JsonArray(jwks)))
        }
}
