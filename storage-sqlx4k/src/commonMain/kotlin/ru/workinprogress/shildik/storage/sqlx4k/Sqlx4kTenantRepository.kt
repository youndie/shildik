package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import ru.workinprogress.shildik.core.model.Tenant
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.TenantRepository

/**
 * Tenants. A **one-to-one** port of the previous repository: the same SQL the old library
 * generated, and the same semantics.
 *
 * The temptation to tidy up along the way is worth resisting: the fewer differences from proven
 * behaviour, the clearer it is that a change of library broke something rather than an
 * improvement made at the same time.
 * (research-native §7.1).
 */
class Sqlx4kTenantRepository(
    private val db: Driver,
) : TenantRepository {
    override suspend fun byRealm(realm: String): Tenant? =
        db
            .query(sql("select id, realm, registration_open from tenants where realm = :realm").bind("realm", realm))
            .firstOrNull()
            ?.let(::toTenant)

    override suspend fun byId(id: TenantId): Tenant? =
        db
            .query(sql("select id, realm, registration_open from tenants where id = :id").bind("id", id.value))
            .firstOrNull()
            ?.let(::toTenant)

    override suspend fun list(): List<Tenant> = db.query(sql("select id, realm, registration_open from tenants")).map(::toTenant)

    override suspend fun create(tenant: Tenant): Tenant {
        db.exec(
            sql("insert into tenants (id, realm, registration_open) values (:id, :realm, :open)")
                .bind("id", tenant.id.value)
                .bind("realm", tenant.realm)
                .bind("open", tenant.registrationOpen),
        )
        return tenant
    }

    private fun toTenant(row: ResultSet.Row) =
        Tenant(
            id = TenantId(row.requiredText("id")),
            realm = row.requiredText("realm"),
            registrationOpen = row.flag("registration_open"),
        )
}
