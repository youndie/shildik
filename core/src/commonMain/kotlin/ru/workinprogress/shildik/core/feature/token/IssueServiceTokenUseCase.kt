package ru.workinprogress.shildik.core.feature.token

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.core.di.IssuerResolver
import ru.workinprogress.shildik.core.feature.keys.ActiveSigningKey
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.core.usecase.UseCase
import ru.workinprogress.shildik.core.usecase.suspendRunCatching
import ru.workinprogress.shildik.crypto.Jws
import ru.workinprogress.shildik.crypto.Secrets
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** A refusal to issue. Outwards every reason looks the same — see [InvalidClient]. */
class InvalidClient : Exception("invalid_client")

/**
 * Issuing a service token via `client_credentials`.
 *
 * The shape of the claims is dictated by validators that are already in production
 * (api/protocol-oidc-subset.md §2): `azp` + `realm_access.roles` is what the relying services read.
 */
class IssueServiceTokenUseCase(
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
    private val activeKey: ActiveSigningKey,
    private val issuers: IssuerResolver,
    private val clock: Clock = Clock.System,
) : UseCase<IssueServiceTokenUseCase.Params, IssuedToken> {
    override suspend fun invoke(params: Params): Result<IssuedToken> =
        suspendRunCatching {
            val tenant = tenants.byRealm(params.realm) ?: throw InvalidClient()
            val client = clients.find(tenant.id, params.clientId) ?: throw InvalidClient()

            // A public client is not entitled to a service token: its "secret" is known to
            // everyone who opened the page (api/protocol-oidc-browser.md §3). Otherwise a public
            // client would gain access to the service-to-service contour.
            if (client.public) throw InvalidClient()

            // We always hash, even when there is no client: otherwise the response time tells
            // whether a client exists — and outwards these cases must not be distinguishable
            // (protocol §2).
            if (!Secrets.matches(client.secretHash, Secrets.hash(params.clientSecret))) throw InvalidClient()

            // After the secret, not before: which resources a client may name is not information
            // somebody who failed to authenticate has any business learning.
            val audience = Audiences.resolve(client, params.resources)

            val key = activeKey.forTenant(tenant.id)
            val now = clock.now()
            val expiresAt = now + TOKEN_TTL

            val claims =
                JsonObject(
                    mapOf(
                        "iss" to JsonPrimitive(issuers.issuerFor(tenant.realm)),
                        "sub" to JsonPrimitive(client.clientId),
                        "azp" to JsonPrimitive(client.clientId),
                        "iat" to JsonPrimitive(now.epochSeconds),
                        "exp" to JsonPrimitive(expiresAt.epochSeconds),
                        "typ" to JsonPrimitive("Bearer"),
                        "realm_access" to
                            JsonObject(
                                mapOf("roles" to JsonArray(client.roles.sorted().map(::JsonPrimitive))),
                            ),
                    ) + (Audiences.claim(audience)?.let { mapOf("aud" to it) } ?: emptyMap()),
                )

            IssuedToken(
                accessToken = Jws.sign(key, claims),
                expiresInSeconds = TOKEN_TTL.inWholeSeconds,
            )
        }

    class Params(
        val realm: String,
        val clientId: String,
        val clientSecret: String,
        /** RFC 8707 `resource`, as many as were sent. Empty means "whatever this client is for". */
        val resources: Set<String> = emptySet(),
    )

    companion object {
        /**
         * Five minutes — comfortably more than the 60-second lead time with which a client refreshes
         * its token. The TTL must not drop below that boundary: the client would then fetch a new
         * token continuously.
         */
        val TOKEN_TTL = 5.minutes
    }
}

data class IssuedToken(
    val accessToken: String,
    val expiresInSeconds: Long,
)
