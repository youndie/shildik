-- Схема shildik целиком, одним файлом.
--
-- Это не перенос двенадцати миграций Flyway, а их схлопывание: историю никто не проигрывает,
-- обе живые базы давно на голове, а с нуля мигрируют только тесты и будущие установки.
-- Двенадцать шагов там, где нужен один, — это двенадцать поводов разойтись.
--
-- Файл снят с базы (`pg_dump --schema-only`), а не написан руками: руками схему повторяют
-- похоже, а нужно точно. Что она совпадает с той, что дают миграции Flyway, проверяет
-- `SchemaParityTest` — пока оба хранилища живут рядом.

CREATE TABLE authorization_codes (
    tenant_id character varying(64) NOT NULL,
    code_hash character varying(128) NOT NULL,
    client_id character varying(128) NOT NULL,
    user_id character varying(255) NOT NULL,
    redirect_uri character varying(512) NOT NULL,
    code_challenge character varying(128) NOT NULL,
    scope character varying(255) NOT NULL,
    nonce character varying(255),
    expires_at bigint NOT NULL,
    used boolean NOT NULL
);

CREATE TABLE client_redirect_uris (
    tenant_id character varying(64) NOT NULL,
    client_id character varying(128) NOT NULL,
    redirect_uri character varying(512) NOT NULL
);

CREATE TABLE client_roles (
    tenant_id character varying(64) NOT NULL,
    client_id character varying(128) NOT NULL,
    role character varying(128) NOT NULL
);

CREATE TABLE clients (
    tenant_id character varying(64) NOT NULL,
    client_id character varying(128) NOT NULL,
    secret_hash character varying(128) NOT NULL,
    public boolean DEFAULT false NOT NULL
);

CREATE TABLE login_attempts (
    tenant_id character varying(64) NOT NULL,
    login character varying(255) NOT NULL,
    failures integer NOT NULL,
    locked_until bigint
);

CREATE TABLE pending_authorizations (
    tenant_id character varying(64) NOT NULL,
    state character varying(128) NOT NULL,
    client_id character varying(128) NOT NULL,
    redirect_uri character varying(512) NOT NULL,
    scope character varying(255) NOT NULL,
    client_state character varying(255),
    nonce character varying(255),
    code_challenge character varying(128) NOT NULL,
    method_id character varying(64) NOT NULL,
    expires_at bigint NOT NULL
);

CREATE TABLE refresh_tokens (
    tenant_id character varying(64) NOT NULL,
    token_hash character varying(128) NOT NULL,
    family character varying(64) NOT NULL,
    client_id character varying(128) NOT NULL,
    user_id character varying(255) NOT NULL,
    scope character varying(255) NOT NULL,
    expires_at bigint NOT NULL,
    used boolean NOT NULL,
    used_at bigint
);

CREATE TABLE signing_keys (
    tenant_id character varying(64) NOT NULL,
    kid character varying(64) NOT NULL,
    private_key bytea NOT NULL,
    state character varying(16) NOT NULL,
    created_at bigint NOT NULL,
    retiring_since bigint
);

CREATE TABLE tenants (
    id character varying(64) NOT NULL,
    realm character varying(128) NOT NULL,
    registration_open boolean DEFAULT true NOT NULL
);

CREATE TABLE user_credentials (
    tenant_id character varying(64) NOT NULL,
    user_id character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL
);

CREATE TABLE user_identities (
    tenant_id character varying(64) NOT NULL,
    user_id character varying(255) NOT NULL,
    provider character varying(64) NOT NULL,
    subject character varying(255) NOT NULL
);

CREATE TABLE users (
    tenant_id character varying(64) NOT NULL,
    id character varying(255) NOT NULL,
    email character varying(255),
    name character varying(255),
    email_verified boolean NOT NULL,
    enabled boolean NOT NULL
);

ALTER TABLE ONLY client_roles
    ADD CONSTRAINT client_roles_pkey PRIMARY KEY (tenant_id, client_id, role);

ALTER TABLE ONLY clients
    ADD CONSTRAINT clients_pkey PRIMARY KEY (tenant_id, client_id);

ALTER TABLE ONLY authorization_codes
    ADD CONSTRAINT pk_authorization_codes PRIMARY KEY (tenant_id, code_hash);

ALTER TABLE ONLY client_redirect_uris
    ADD CONSTRAINT pk_client_redirect_uris PRIMARY KEY (tenant_id, client_id, redirect_uri);

ALTER TABLE ONLY login_attempts
    ADD CONSTRAINT pk_login_attempts PRIMARY KEY (tenant_id, login);

ALTER TABLE ONLY pending_authorizations
    ADD CONSTRAINT pk_pending_authorizations PRIMARY KEY (tenant_id, state);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT pk_refresh_tokens PRIMARY KEY (tenant_id, token_hash);

ALTER TABLE ONLY user_credentials
    ADD CONSTRAINT pk_user_credentials PRIMARY KEY (tenant_id, user_id);

ALTER TABLE ONLY user_identities
    ADD CONSTRAINT pk_user_identities PRIMARY KEY (tenant_id, provider, subject);

ALTER TABLE ONLY users
    ADD CONSTRAINT pk_users PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY signing_keys
    ADD CONSTRAINT signing_keys_pkey PRIMARY KEY (tenant_id, kid);

ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_realm_key UNIQUE (realm);

CREATE INDEX authorization_codes_expires_at ON authorization_codes USING btree (expires_at);

CREATE INDEX pending_authorizations_expires_at ON pending_authorizations USING btree (expires_at);

CREATE INDEX refresh_tokens_expires_at ON refresh_tokens USING btree (expires_at);

CREATE INDEX refresh_tokens_tenant_id_family ON refresh_tokens USING btree (tenant_id, family);

CREATE INDEX signing_keys_tenant_id_state ON signing_keys USING btree (tenant_id, state);

CREATE INDEX user_identities_tenant_id_user_id ON user_identities USING btree (tenant_id, user_id);

CREATE INDEX users_tenant_id_email ON users USING btree (tenant_id, email);

ALTER TABLE ONLY client_roles
    ADD CONSTRAINT client_roles_tenant_id_client_id_fkey FOREIGN KEY (tenant_id, client_id) REFERENCES clients(tenant_id, client_id) ON DELETE CASCADE;

ALTER TABLE ONLY authorization_codes
    ADD CONSTRAINT fk_authorization_codes_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY clients
    ADD CONSTRAINT fk_clients_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY login_attempts
    ADD CONSTRAINT fk_login_attempts_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY pending_authorizations
    ADD CONSTRAINT fk_pending_authorizations_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY signing_keys
    ADD CONSTRAINT fk_signing_keys_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY user_credentials
    ADD CONSTRAINT fk_user_credentials_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE ONLY users
    ADD CONSTRAINT fk_users_tenant_id__id FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
