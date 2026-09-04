package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.asByteArray
import ru.workinprogress.shildik.core.model.KeyState
import ru.workinprogress.shildik.core.model.SigningKeyRecord
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.KeyRepository
import kotlin.time.Clock
import kotlin.time.Instant

class Sqlx4kKeyRepository(
    private val db: Driver,
) : KeyRepository {
    override suspend fun active(tenantId: TenantId): SigningKeyRecord? =
        db
            .query(
                sql("select * from signing_keys where tenant_id = :tenant and state = :state")
                    .bind("tenant", tenantId.value)
                    .bind("state", KeyState.ACTIVE.name),
            ).firstOrNull()
            ?.let(::toRecord)

    /**
     * Published means the current one and the one on its way out: somebody else's JWKS cache
     * lives for a day, and tokens issued five minutes ago have to keep verifying after a rotation.
     */
    override suspend fun published(tenantId: TenantId): List<SigningKeyRecord> =
        all(tenantId).filter { it.state == KeyState.ACTIVE || it.state == KeyState.RETIRING }

    override suspend fun all(tenantId: TenantId): List<SigningKeyRecord> =
        db
            .query(sql("select * from signing_keys where tenant_id = :tenant").bind("tenant", tenantId.value))
            .map(::toRecord)

    /**
     * An upsert rather than an insert: rotating the master key rereads the keys, encrypts them
     * again and puts them back **under the same kid**. A plain insert would fail there on
     * a unique index — the previous version upserts here too.
     */
    override suspend fun save(record: SigningKeyRecord) {
        db.exec(
            sql(
                "insert into signing_keys (tenant_id, kid, private_key, state, created_at, retiring_since) " +
                    "values (:tenant, :kid, :key, :state, :created, :retiring) " +
                    "on conflict (tenant_id, kid) do update set " +
                    "private_key = excluded.private_key, state = excluded.state, " +
                    "created_at = excluded.created_at, retiring_since = excluded.retiring_since",
            ).bind("tenant", record.tenantId.value)
                .bind("kid", record.kid)
                .bind("key", record.privateKeyDer)
                .bind("state", record.state.name)
                .bind("created", record.createdAt.toEpochMilliseconds())
                .bind("retiring", record.retiringSince?.toEpochMilliseconds()),
        )
    }

    /**
     * The moment of retirement is stamped **together** with the status: `RETIRING` without a date
     * is a key that can never be deleted, because there is no telling how long it has waited.
     */
    override suspend fun updateState(
        tenantId: TenantId,
        kid: String,
        state: KeyState,
    ) {
        val statement =
            if (state == KeyState.RETIRING) {
                sql(
                    "update signing_keys set state = :state, retiring_since = :since " +
                        "where tenant_id = :tenant and kid = :kid",
                ).bind("since", Clock.System.now().toEpochMilliseconds())
            } else {
                sql("update signing_keys set state = :state where tenant_id = :tenant and kid = :kid")
            }

        db.exec(statement.bind("state", state.name).bind("tenant", tenantId.value).bind("kid", kid))
    }

    private fun toRecord(row: ResultSet.Row) =
        SigningKeyRecord(
            tenantId = TenantId(row.requiredText("tenant_id")),
            kid = row.requiredText("kid"),
            privateKeyDer = row.get("private_key").asByteArray(),
            state = KeyState.valueOf(row.requiredText("state")),
            createdAt = Instant.fromEpochMilliseconds(row.number("created_at")),
            retiringSince = row.numberOrNull("retiring_since")?.let(Instant::fromEpochMilliseconds),
        )
}
