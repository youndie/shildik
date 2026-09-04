-- Which resources a client may hold a token for (RFC 8707).
--
-- The SQLite counterpart of the Postgres migration with the same number. There the table is
-- created and its keys are added by `ALTER TABLE`; here they are part of the `CREATE TABLE`,
-- because SQLite has no way to add a constraint afterwards. The result is the same table.
CREATE TABLE client_audiences (
    tenant_id text NOT NULL,
    client_id text NOT NULL,
    audience text NOT NULL,
    PRIMARY KEY (tenant_id, client_id, audience),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, client_id) ON DELETE CASCADE
);
