-- The whole schema, in SQLite's own terms.
--
-- A translation of the Postgres schema next door (`storage-sqlx4k/…/migrations/1_schema.sql`),
-- not a second design: same tables, same columns, same keys and indexes. `SchemaParityTest` in
-- this module compares the two lists so the pair cannot drift apart unnoticed.
--
-- What had to change, and nothing else did:
--
--   * `character varying(n)` → `text`. SQLite does not enforce a declared length — writing
--     `varchar(64)` here would read as a constraint and behave as a comment. The lengths live in
--     the domain, which is where they are actually checked.
--   * `boolean` → `integer`. SQLite has no boolean type; `true`/`false` in a statement are 1 and 0,
--     which is what the repositories write and what `asBoolean()` reads back.
--   * `bigint` → `integer`. In SQLite that is a 64-bit signed integer — the epoch milliseconds
--     every timestamp here is stored as.
--   * `bytea` → `blob`.
--   * Constraints are declared **inside** `CREATE TABLE`. SQLite has no
--     `ALTER TABLE … ADD CONSTRAINT`, so the primary and foreign keys that stand as separate
--     statements in the Postgres file are folded into the tables here.
--   * `USING btree` is dropped: SQLite has one kind of index.
--
-- The order of the statements is the order of the foreign keys: a table is created after the one
-- it points at.

CREATE TABLE tenants (
    id text NOT NULL,
    realm text NOT NULL,
    registration_open integer DEFAULT 1 NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (realm)
);

CREATE TABLE clients (
    tenant_id text NOT NULL,
    client_id text NOT NULL,
    secret_hash text NOT NULL,
    public integer DEFAULT 0 NOT NULL,
    PRIMARY KEY (tenant_id, client_id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE client_roles (
    tenant_id text NOT NULL,
    client_id text NOT NULL,
    role text NOT NULL,
    PRIMARY KEY (tenant_id, client_id, role),
    FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, client_id) ON DELETE CASCADE
);

CREATE TABLE client_redirect_uris (
    tenant_id text NOT NULL,
    client_id text NOT NULL,
    redirect_uri text NOT NULL,
    PRIMARY KEY (tenant_id, client_id, redirect_uri)
);

CREATE TABLE users (
    tenant_id text NOT NULL,
    id text NOT NULL,
    email text,
    name text,
    email_verified integer NOT NULL,
    enabled integer NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE user_credentials (
    tenant_id text NOT NULL,
    user_id text NOT NULL,
    password_hash text NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE user_identities (
    tenant_id text NOT NULL,
    user_id text NOT NULL,
    provider text NOT NULL,
    subject text NOT NULL,
    PRIMARY KEY (tenant_id, provider, subject)
);

CREATE TABLE authorization_codes (
    tenant_id text NOT NULL,
    code_hash text NOT NULL,
    client_id text NOT NULL,
    user_id text NOT NULL,
    redirect_uri text NOT NULL,
    code_challenge text NOT NULL,
    scope text NOT NULL,
    nonce text,
    expires_at integer NOT NULL,
    used integer NOT NULL,
    PRIMARY KEY (tenant_id, code_hash),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE pending_authorizations (
    tenant_id text NOT NULL,
    state text NOT NULL,
    client_id text NOT NULL,
    redirect_uri text NOT NULL,
    scope text NOT NULL,
    client_state text,
    nonce text,
    code_challenge text NOT NULL,
    method_id text NOT NULL,
    expires_at integer NOT NULL,
    PRIMARY KEY (tenant_id, state),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE refresh_tokens (
    tenant_id text NOT NULL,
    token_hash text NOT NULL,
    family text NOT NULL,
    client_id text NOT NULL,
    user_id text NOT NULL,
    scope text NOT NULL,
    expires_at integer NOT NULL,
    used integer NOT NULL,
    used_at integer,
    PRIMARY KEY (tenant_id, token_hash),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE login_attempts (
    tenant_id text NOT NULL,
    login text NOT NULL,
    failures integer NOT NULL,
    locked_until integer,
    PRIMARY KEY (tenant_id, login),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE signing_keys (
    tenant_id text NOT NULL,
    kid text NOT NULL,
    private_key blob NOT NULL,
    state text NOT NULL,
    created_at integer NOT NULL,
    retiring_since integer,
    PRIMARY KEY (tenant_id, kid),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE INDEX authorization_codes_expires_at ON authorization_codes (expires_at);

CREATE INDEX pending_authorizations_expires_at ON pending_authorizations (expires_at);

CREATE INDEX refresh_tokens_expires_at ON refresh_tokens (expires_at);

CREATE INDEX refresh_tokens_tenant_id_family ON refresh_tokens (tenant_id, family);

CREATE INDEX signing_keys_tenant_id_state ON signing_keys (tenant_id, state);

CREATE INDEX user_identities_tenant_id_user_id ON user_identities (tenant_id, user_id);

CREATE INDEX users_tenant_id_email ON users (tenant_id, email);
