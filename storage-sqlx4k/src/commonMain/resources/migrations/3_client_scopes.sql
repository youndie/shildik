-- What a client's tokens may permit where they are spent (RFC 6749 §3.3).
--
-- Next to `client_audiences` and shaped the same way, because it answers the neighbouring half of
-- the same question: the audience says which service will take the token, this says what may be
-- done there. A resource server built on OAuth asks the second question of `scope` and refuses a
-- token without it, however right its audience.
--
-- Nobody has any rows here after this runs, and that is the migration behaving correctly: an
-- existing client keeps getting tokens with no `scope` claim at all, exactly as before.
CREATE TABLE client_scopes (
    tenant_id character varying(64) NOT NULL,
    client_id character varying(128) NOT NULL,
    scope character varying(256) NOT NULL
);

ALTER TABLE ONLY client_scopes
    ADD CONSTRAINT pk_client_scopes PRIMARY KEY (tenant_id, client_id, scope);

ALTER TABLE ONLY client_scopes
    ADD CONSTRAINT fk_client_scopes_client FOREIGN KEY (tenant_id, client_id)
    REFERENCES clients (tenant_id, client_id) ON DELETE CASCADE;
