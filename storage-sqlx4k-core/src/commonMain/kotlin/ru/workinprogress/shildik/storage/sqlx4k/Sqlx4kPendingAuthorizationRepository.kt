package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import ru.workinprogress.shildik.core.model.PendingAuthorization
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.PendingAuthorizationRepository
import kotlin.time.Instant

class Sqlx4kPendingAuthorizationRepository(
    private val db: Driver,
) : PendingAuthorizationRepository {
    override suspend fun save(pending: PendingAuthorization) {
        db.exec(
            sql(
                "insert into pending_authorizations " +
                    "(tenant_id, state, client_id, redirect_uri, scope, client_state, nonce, " +
                    "code_challenge, method_id, expires_at) " +
                    "values (:tenant, :state, :client, :redirect, :scope, :clientState, :nonce, :challenge, :method, :expires)",
            ).bind("tenant", pending.tenantId.value)
                .bind("state", pending.state)
                .bind("client", pending.clientId)
                .bind("redirect", pending.redirectUri)
                .bind("scope", pending.scope)
                .bind("clientState", pending.clientState)
                .bind("nonce", pending.nonce)
                .bind("challenge", pending.codeChallenge)
                .bind("method", pending.methodId)
                .bind("expires", pending.expiresAt.toEpochMilliseconds()),
        )
    }

    override suspend fun take(
        tenantId: TenantId,
        state: String,
    ): PendingAuthorization? {
        val found = find(tenantId, state) ?: return null
        // Taken rather than read: a second return with the same `state` is a sign of somebody
        // replaying an answer, and it must not work twice.
        delete(tenantId, state)
        return found
    }

    override suspend fun find(
        tenantId: TenantId,
        state: String,
    ): PendingAuthorization? =
        db
            .query(
                sql("select * from pending_authorizations where tenant_id = :tenant and state = :state")
                    .bind("tenant", tenantId.value)
                    .bind("state", state),
            ).firstOrNull()
            ?.let(::toPending)

    override suspend fun delete(
        tenantId: TenantId,
        state: String,
    ) {
        db.exec(
            sql("delete from pending_authorizations where tenant_id = :tenant and state = :state")
                .bind("tenant", tenantId.value)
                .bind("state", state),
        )
    }

    private fun toPending(row: ResultSet.Row) =
        PendingAuthorization(
            tenantId = TenantId(row.requiredText("tenant_id")),
            state = row.requiredText("state"),
            clientId = row.requiredText("client_id"),
            redirectUri = row.requiredText("redirect_uri"),
            scope = row.requiredText("scope"),
            clientState = row.text("client_state"),
            nonce = row.text("nonce"),
            codeChallenge = row.requiredText("code_challenge"),
            methodId = row.requiredText("method_id"),
            expiresAt = Instant.fromEpochMilliseconds(row.number("expires_at")),
        )
}
