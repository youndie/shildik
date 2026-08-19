package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import ru.workinprogress.shildik.core.model.LoginAttempt
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.LoginAttemptRepository
import kotlin.time.Instant

class Sqlx4kLoginAttemptRepository(
    private val db: Driver,
) : LoginAttemptRepository {
    override suspend fun find(
        tenantId: TenantId,
        login: String,
    ): LoginAttempt? =
        db
            .query(
                sql("select * from login_attempts where tenant_id = :tenant and login = :login")
                    .bind("tenant", tenantId.value)
                    .bind("login", login),
            ).firstOrNull()
            ?.let { row ->
                LoginAttempt(
                    tenantId = TenantId(row.requiredText("tenant_id")),
                    login = row.requiredText("login"),
                    failures = row.number("failures").toInt(),
                    lockedUntil = row.numberOrNull("locked_until")?.let(Instant::fromEpochMilliseconds),
                )
            }

    override suspend fun save(attempt: LoginAttempt) {
        db.exec(
            sql(
                "insert into login_attempts (tenant_id, login, failures, locked_until) " +
                    "values (:tenant, :login, :failures, :locked) " +
                    "on conflict (tenant_id, login) do update set " +
                    "failures = excluded.failures, locked_until = excluded.locked_until",
            ).bind("tenant", attempt.tenantId.value)
                .bind("login", attempt.login)
                .bind("failures", attempt.failures)
                .bind("locked", attempt.lockedUntil?.toEpochMilliseconds()),
        )
    }

    override suspend fun reset(
        tenantId: TenantId,
        login: String,
    ) {
        db.exec(
            sql("delete from login_attempts where tenant_id = :tenant and login = :login")
                .bind("tenant", tenantId.value)
                .bind("login", login),
        )
    }
}
