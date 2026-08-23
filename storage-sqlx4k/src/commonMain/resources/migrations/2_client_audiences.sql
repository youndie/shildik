-- Which resources a client may hold a token for (RFC 8707).
--
-- Its own table for the same reason the redirect addresses have one: a set belongs in rows, and a
-- comma-separated column is a parser waiting to be written twice.
--
-- Nobody has any rows here after this runs, and that is the migration behaving correctly: an
-- existing client keeps getting tokens shaped exactly as before, with no `aud`. A resource server
-- that checks the audience starts accepting them only once somebody says which resource the client
-- is for.
CREATE TABLE client_audiences (
    tenant_id character varying(64) NOT NULL,
    client_id character varying(128) NOT NULL,
    audience character varying(512) NOT NULL
);

ALTER TABLE ONLY client_audiences
    ADD CONSTRAINT pk_client_audiences PRIMARY KEY (tenant_id, client_id, audience);

ALTER TABLE ONLY client_audiences
    ADD CONSTRAINT fk_client_audiences_client FOREIGN KEY (tenant_id, client_id)
    REFERENCES clients (tenant_id, client_id) ON DELETE CASCADE;
