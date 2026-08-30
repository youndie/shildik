package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import ru.workinprogress.shildik.core.model.AuthorizationCode
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.AuthorizationCodeRepository
import kotlin.time.Instant

class Sqlx4kAuthorizationCodeRepository(
    private val db: Driver,
) : AuthorizationCodeRepository {
    override suspend fun save(code: AuthorizationCode) {
        db.exec(
            sql(
                "insert into authorization_codes " +
                    "(tenant_id, code_hash, client_id, user_id, redirect_uri, code_challenge, " +
                    "scope, nonce, expires_at, used) " +
                    "values (:tenant, :hash, :client, :user, :redirect, :challenge, :scope, :nonce, :expires, :used)",
            ).bind("tenant", code.tenantId.value)
                .bind("hash", code.codeHash)
                .bind("client", code.clientId)
                .bind("user", code.userId)
                .bind("redirect", code.redirectUri)
                .bind("challenge", code.codeChallenge)
                .bind("scope", code.scope)
                .bind("nonce", code.nonce)
                .bind("expires", code.expiresAt.toEpochMilliseconds())
                .bind("used", code.used),
        )
    }

    override suspend fun find(
        tenantId: TenantId,
        codeHash: String,
    ): AuthorizationCode? =
        db
            .query(
                sql("select * from authorization_codes where tenant_id = :tenant and code_hash = :hash")
                    .bind("tenant", tenantId.value)
                    .bind("hash", codeHash),
            ).firstOrNull()
            ?.let { row ->
                AuthorizationCode(
                    tenantId = TenantId(row.requiredText("tenant_id")),
                    codeHash = row.requiredText("code_hash"),
                    clientId = row.requiredText("client_id"),
                    userId = row.requiredText("user_id"),
                    redirectUri = row.requiredText("redirect_uri"),
                    codeChallenge = row.requiredText("code_challenge"),
                    scope = row.requiredText("scope"),
                    nonce = row.text("nonce"),
                    expiresAt = Instant.fromEpochMilliseconds(row.number("expires_at")),
                    used = row.flag("used"),
                )
            }

    /**
     * The marking is a conditional `UPDATE ... WHERE used = false`: single use rests on the
     * database itself rather than on "read, check, write". Two simultaneous exchanges produce one
     * successful marking, and the second gets zero rows.
     */
    override suspend fun markUsed(
        tenantId: TenantId,
        codeHash: String,
    ): Boolean =
        db.exec(
            sql(
                "update authorization_codes set used = true where tenant_id = :tenant and code_hash = :hash and used = false",
            ).bind("tenant", tenantId.value)
                .bind("hash", codeHash),
        ) > 0
}
