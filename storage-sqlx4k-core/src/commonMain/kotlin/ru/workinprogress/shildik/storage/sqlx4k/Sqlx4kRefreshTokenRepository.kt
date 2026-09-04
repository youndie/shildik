package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import ru.workinprogress.shildik.core.model.RefreshToken
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.RefreshTokenRepository
import kotlin.time.Clock
import kotlin.time.Instant

class Sqlx4kRefreshTokenRepository(
    private val db: Driver,
) : RefreshTokenRepository {
    override suspend fun save(token: RefreshToken) {
        db.exec(
            sql(
                "insert into refresh_tokens " +
                    "(tenant_id, token_hash, family, client_id, user_id, scope, expires_at, used, used_at) " +
                    "values (:tenant, :hash, :family, :client, :user, :scope, :expires, :used, :usedAt)",
            ).bind("tenant", token.tenantId.value)
                .bind("hash", token.tokenHash)
                .bind("family", token.family)
                .bind("client", token.clientId)
                .bind("user", token.userId)
                .bind("scope", token.scope)
                .bind("expires", token.expiresAt.toEpochMilliseconds())
                .bind("used", token.used)
                .bind("usedAt", token.usedAt?.toEpochMilliseconds()),
        )
    }

    override suspend fun find(
        tenantId: TenantId,
        tokenHash: String,
    ): RefreshToken? =
        db
            .query(
                sql("select * from refresh_tokens where tenant_id = :tenant and token_hash = :hash")
                    .bind("tenant", tenantId.value)
                    .bind("hash", tokenHash),
            ).firstOrNull()
            ?.let { row ->
                RefreshToken(
                    tenantId = TenantId(row.requiredText("tenant_id")),
                    tokenHash = row.requiredText("token_hash"),
                    family = row.requiredText("family"),
                    clientId = row.requiredText("client_id"),
                    userId = row.requiredText("user_id"),
                    scope = row.requiredText("scope"),
                    expiresAt = Instant.fromEpochMilliseconds(row.number("expires_at")),
                    used = row.flag("used"),
                    usedAt = row.numberOrNull("used_at")?.let(Instant::fromEpochMilliseconds),
                )
            }

    /** The marking is conditional: whoever loses the race gets zero rows, and that is a race, not a leak. */
    override suspend fun markUsed(
        tenantId: TenantId,
        tokenHash: String,
    ): Boolean =
        db.exec(
            sql(
                "update refresh_tokens set used = true, used_at = :now " +
                    "where tenant_id = :tenant and token_hash = :hash and used = false",
            ).bind("now", Clock.System.now().toEpochMilliseconds())
                .bind("tenant", tenantId.value)
                .bind("hash", tokenHash),
        ) > 0

    override suspend fun revokeFamily(
        tenantId: TenantId,
        family: String,
    ) {
        db.exec(
            sql("update refresh_tokens set used = true where tenant_id = :tenant and family = :family")
                .bind("tenant", tenantId.value)
                .bind("family", family),
        )
    }

    override suspend fun revokeForUser(
        tenantId: TenantId,
        clientId: String,
        userId: String,
    ) {
        db.exec(
            sql(
                "update refresh_tokens set used = true " +
                    "where tenant_id = :tenant and client_id = :client and user_id = :user",
            ).bind("tenant", tenantId.value)
                .bind("client", clientId)
                .bind("user", userId),
        )
    }
}
