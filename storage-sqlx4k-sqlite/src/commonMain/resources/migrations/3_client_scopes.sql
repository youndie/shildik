-- What a client's tokens may permit where they are spent (RFC 6749 §3.3).
--
-- Next to `client_audiences` and shaped the same way, as in the Postgres set; the keys are
-- declared inside the table for the same reason as there.
CREATE TABLE client_scopes (
    tenant_id text NOT NULL,
    client_id text NOT NULL,
    scope text NOT NULL,
    PRIMARY KEY (tenant_id, client_id, scope),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, client_id) ON DELETE CASCADE
);
