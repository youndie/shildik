package ru.workinprogress.shildik.core.feature.token

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.shildik.core.di.IssuerResolver
import ru.workinprogress.shildik.core.feature.keys.ActiveSigningKey
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.crypto.Jws
import kotlin.time.Clock

/**
 * Tokens for a person: an `access_token` and an `id_token`.
 *
 * Both are needed, and that is not redundancy. The `access_token` is presented to services and
 * lives for minutes; the `id_token` proves **that a sign-in happened** and is read by the
 * application itself — without it next-auth creates no session, however many access tokens we hand
 * out (api/protocol-oidc-browser.md §1).
 *
 * The key difference from a service token: `sub` is a **person's** identifier, and it comes from
 * storage rather than being invented here. For those imported from Keycloak it is their previous
 * identifier, otherwise the relying service will not recognise the owner (feature-user-import §2).
 */
class IssueUserTokensUseCase(
    private val activeKey: ActiveSigningKey,
    private val issuers: IssuerResolver,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        tenant: Tenant,
        user: User,
        clientId: String,
        nonce: String?,
        scope: String,
        audience: Set<String> = emptySet(),
        permissions: Set<String> = emptySet(),
    ): UserTokens {
        val key = activeKey.forTenant(tenant.id)
        val now = clock.now()
        val issuer = issuers.issuerFor(tenant.realm)
        val expiresAt = now + IssueServiceTokenUseCase.TOKEN_TTL

        val common =
            mapOf(
                "iss" to JsonPrimitive(issuer),
                "sub" to JsonPrimitive(user.id),
                "azp" to JsonPrimitive(clientId),
                "iat" to JsonPrimitive(now.epochSeconds),
                "exp" to JsonPrimitive(expiresAt.epochSeconds),
            )

        val profile =
            buildMap {
                user.email?.let {
                    put("email", JsonPrimitive(it))
                    put("email_verified", JsonPrimitive(user.emailVerified))
                }
                user.name?.let { put("name", JsonPrimitive(it)) }
            }

        // `email` in the access token is not decoration: the rule `email != null && azp ==
        // "web-app"` in three relying services tells a person from a program by it.
        // The access token is the one presented to services, so it is the one that carries the
        // audience. The id_token below keeps naming the client instead: it is addressed to whoever
        // started the sign-in and is not meant to travel on.
        val accessClaims =
            JsonObject(
                common + profile + mapOf("typ" to JsonPrimitive("Bearer")) +
                    (Audiences.claim(audience)?.let { mapOf("aud" to it) } ?: emptyMap()) +
                    // The permissions go into the access token only. The id_token says who signed
                    // in, and what they may do somewhere else is none of its business.
                    (Scopes.claim(permissions)?.let { mapOf("scope" to JsonPrimitive(it)) } ?: emptyMap()),
            )

        val idClaims =
            JsonObject(
                common + profile +
                    buildMap {
                        // The `aud` of an id_token is the client, not a service: this token is
                        // addressed to whoever started the sign-in and is not meant to travel on.
                        put("aud", JsonPrimitive(clientId))
                        put("typ", JsonPrimitive("ID"))
                        // Without carrying the nonce over, an id_token accepts a replay of
                        // somebody else's answer — next-auth checks it against what it sent.
                        nonce?.let { put("nonce", JsonPrimitive(it)) }
                    },
            )

        return UserTokens(
            accessToken = Jws.sign(key, accessClaims),
            idToken = Jws.sign(key, idClaims),
            expiresInSeconds = IssueServiceTokenUseCase.TOKEN_TTL.inWholeSeconds,
            // What came out, not what was asked for — RFC 6749 §5.1 asks for the difference to be
            // stated, and there is one whenever a client holds a permission it did not name. The
            // protocol scopes are kept because the client compares them: `offline_access` is why it
            // has a refresh token in its hands.
            scope = Scopes.claim(Scopes.parse(scope).intersect(Scopes.PROTOCOL) + permissions).orEmpty(),
        )
    }
}

data class UserTokens(
    val accessToken: String,
    val idToken: String,
    val expiresInSeconds: Long,
    val scope: String,
)
