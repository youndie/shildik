package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.CredentialRepository

class Sqlx4kCredentialRepository(
    private val db: Driver,
) : CredentialRepository {
    override suspend fun find(
        tenantId: TenantId,
        userId: String,
    ): String? =
        db
            .query(
                sql("select password_hash from user_credentials where tenant_id = :tenant and user_id = :user")
                    .bind("tenant", tenantId.value)
                    .bind("user", userId),
            ).firstOrNull()
            ?.requiredText("password_hash")

    override suspend fun put(
        tenantId: TenantId,
        userId: String,
        passwordHash: String,
    ) {
        db.exec(
            sql(
                "insert into user_credentials (tenant_id, user_id, password_hash) values (:tenant, :user, :hash) " +
                    "on conflict (tenant_id, user_id) do update set password_hash = excluded.password_hash",
            ).bind("tenant", tenantId.value)
                .bind("user", userId)
                .bind("hash", passwordHash),
        )
    }

    override suspend fun delete(
        tenantId: TenantId,
        userId: String,
    ) {
        db.exec(
            sql("delete from user_credentials where tenant_id = :tenant and user_id = :user")
                .bind("tenant", tenantId.value)
                .bind("user", userId),
        )
    }
}
