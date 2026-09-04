package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.model.User
import ru.workinprogress.shildik.core.port.UserRepository

private const val USER_COLUMNS = "tenant_id, id, email, name, email_verified, enabled"

/**
 * People and the ways they sign in.
 *
 * Identities live in a table of their own and are **replaced wholesale** on save: a migration
 * describes a state rather than adding to what was there.
 */
class Sqlx4kUserRepository(
    private val db: Driver,
) : UserRepository {
    override suspend fun find(
        tenantId: TenantId,
        id: String,
    ): User? =
        db
            .query(
                sql("select $USER_COLUMNS from users where tenant_id = :tenant and id = :id")
                    .bind("tenant", tenantId.value)
                    .bind("id", id),
            ).firstOrNull()
            ?.let { toUser(it, identitiesOf(tenantId, id)) }

    override suspend fun findByIdentity(
        tenantId: TenantId,
        identity: ExternalIdentity,
    ): User? {
        val userId =
            db
                .query(
                    sql(
                        "select user_id from user_identities " +
                            "where tenant_id = :tenant and provider = :provider and subject = :subject",
                    ).bind("tenant", tenantId.value)
                        .bind("provider", identity.provider)
                        .bind("subject", identity.subject),
                ).firstOrNull()
                ?.requiredText("user_id") ?: return null

        return find(tenantId, userId)
    }

    override suspend fun findByEmail(
        tenantId: TenantId,
        email: String,
    ): User? =
        db
            .query(
                sql("select $USER_COLUMNS from users where tenant_id = :tenant and email = :email")
                    .bind("tenant", tenantId.value)
                    .bind("email", email),
            ).firstOrNull()
            ?.let { toUser(it, identitiesOf(tenantId, it.requiredText("id"))) }

    override suspend fun list(tenantId: TenantId): List<User> =
        db
            .query(sql("select $USER_COLUMNS from users where tenant_id = :tenant").bind("tenant", tenantId.value))
            .map { toUser(it, identitiesOf(tenantId, it.requiredText("id"))) }

    override suspend fun upsert(user: User) {
        db.exec(
            sql(
                "insert into users (tenant_id, id, email, name, email_verified, enabled) " +
                    "values (:tenant, :id, :email, :name, :verified, :enabled) " +
                    "on conflict (tenant_id, id) do update set " +
                    "email = excluded.email, name = excluded.name, " +
                    "email_verified = excluded.email_verified, enabled = excluded.enabled",
            ).bind("tenant", user.tenantId.value)
                .bind("id", user.id)
                .bind("email", user.email)
                .bind("name", user.name)
                .bind("verified", user.emailVerified)
                .bind("enabled", user.enabled),
        )

        db.exec(
            sql("delete from user_identities where tenant_id = :tenant and user_id = :user")
                .bind("tenant", user.tenantId.value)
                .bind("user", user.id),
        )

        user.identities.forEach { identity ->
            db.exec(
                sql(
                    "insert into user_identities (tenant_id, user_id, provider, subject) " +
                        "values (:tenant, :user, :provider, :subject)",
                ).bind("tenant", user.tenantId.value)
                    .bind("user", user.id)
                    .bind("provider", identity.provider)
                    .bind("subject", identity.subject),
            )
        }
    }

    private suspend fun identitiesOf(
        tenantId: TenantId,
        userId: String,
    ): Set<ExternalIdentity> =
        db
            .query(
                sql("select provider, subject from user_identities where tenant_id = :tenant and user_id = :user")
                    .bind("tenant", tenantId.value)
                    .bind("user", userId),
            ).map { ExternalIdentity(it.requiredText("provider"), it.requiredText("subject")) }
            .toSet()

    private fun toUser(
        row: ResultSet.Row,
        identities: Set<ExternalIdentity>,
    ) = User(
        tenantId = TenantId(row.requiredText("tenant_id")),
        id = row.requiredText("id"),
        email = row.text("email"),
        name = row.text("name"),
        emailVerified = row.flag("email_verified"),
        enabled = row.flag("enabled"),
        identities = identities,
    )
}
