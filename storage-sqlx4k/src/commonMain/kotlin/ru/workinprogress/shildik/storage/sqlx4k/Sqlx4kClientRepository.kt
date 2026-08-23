package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import ru.workinprogress.shildik.core.model.Client
import ru.workinprogress.shildik.core.model.TenantId
import ru.workinprogress.shildik.core.port.ClientRepository

/**
 * Clients, their roles and their redirect addresses.
 *
 * Roles and addresses are replaced **wholesale**: a configuration describes the state of a client
 * rather than adding to what was there.
 */
class Sqlx4kClientRepository(
    private val db: Driver,
) : ClientRepository {
    override suspend fun find(
        tenantId: TenantId,
        clientId: String,
    ): Client? =
        db
            .query(
                sql("select * from clients where tenant_id = :tenant and client_id = :client")
                    .bind("tenant", tenantId.value)
                    .bind("client", clientId),
            ).firstOrNull()
            ?.let { toClient(it) }

    override suspend fun list(tenantId: TenantId): List<Client> =
        db
            .query(sql("select * from clients where tenant_id = :tenant").bind("tenant", tenantId.value))
            .map { toClient(it) }

    override suspend fun upsert(client: Client) {
        db.exec(
            sql(
                "insert into clients (tenant_id, client_id, secret_hash, \"public\") " +
                    "values (:tenant, :client, :secret, :public) " +
                    "on conflict (tenant_id, client_id) do update set " +
                    "secret_hash = excluded.secret_hash, \"public\" = excluded.\"public\"",
            ).bind("tenant", client.tenantId.value)
                .bind("client", client.clientId)
                .bind("secret", client.secretHash)
                .bind("public", client.public),
        )

        replace("client_redirect_uris", "redirect_uri", client, client.redirectUris)
        replace("client_roles", "role", client, client.roles)
        replace("client_audiences", "audience", client, client.audiences)
    }

    override suspend fun delete(
        tenantId: TenantId,
        clientId: String,
    ) {
        // The order matters: roles and addresses have a foreign key on the client.
        listOf("client_roles", "client_redirect_uris", "client_audiences").forEach { table ->
            db.exec(
                sql("delete from $table where tenant_id = :tenant and client_id = :client")
                    .bind("tenant", tenantId.value)
                    .bind("client", clientId),
            )
        }
        db.exec(
            sql("delete from clients where tenant_id = :tenant and client_id = :client")
                .bind("tenant", tenantId.value)
                .bind("client", clientId),
        )
    }

    private suspend fun replace(
        table: String,
        column: String,
        client: Client,
        values: Set<String>,
    ) {
        db.exec(
            sql("delete from $table where tenant_id = :tenant and client_id = :client")
                .bind("tenant", client.tenantId.value)
                .bind("client", client.clientId),
        )
        values.forEach { value ->
            db.exec(
                sql("insert into $table (tenant_id, client_id, $column) values (:tenant, :client, :value)")
                    .bind("tenant", client.tenantId.value)
                    .bind("client", client.clientId)
                    .bind("value", value),
            )
        }
    }

    private suspend fun valuesOf(
        table: String,
        column: String,
        tenantId: TenantId,
        clientId: String,
    ): Set<String> =
        db
            .query(
                sql("select $column from $table where tenant_id = :tenant and client_id = :client")
                    .bind("tenant", tenantId.value)
                    .bind("client", clientId),
            ).map { it.requiredText(column) }
            .toSet()

    private suspend fun toClient(row: ResultSet.Row): Client {
        val tenantId = TenantId(row.requiredText("tenant_id"))
        val clientId = row.requiredText("client_id")
        return Client(
            tenantId = tenantId,
            clientId = clientId,
            secretHash = row.requiredText("secret_hash"),
            roles = valuesOf("client_roles", "role", tenantId, clientId),
            public = row.flag("public"),
            redirectUris = valuesOf("client_redirect_uris", "redirect_uri", tenantId, clientId),
            audiences = valuesOf("client_audiences", "audience", tenantId, clientId),
        )
    }
}
