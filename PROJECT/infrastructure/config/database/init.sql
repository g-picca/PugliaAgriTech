-- UTENTE E SCHEMA IAM (KEYCLOAK)
CREATE USER keycloak_admin WITH PASSWORD 'keycloak_admin';
CREATE SCHEMA iam AUTHORIZATION keycloak_admin;
GRANT ALL PRIVILEGES ON SCHEMA iam TO keycloak_admin;

-- UTENTE PER APP AGRITECH
CREATE USER agritech_admin WITH PASSWORD 'agritech_admin';
-- SCHEMA AGRITECH
CREATE SCHEMA agritech AUTHORIZATION agritech_admin;
GRANT CONNECT ON DATABASE agritech_db TO agritech_admin;
GRANT ALL PRIVILEGES ON SCHEMA agritech TO agritech_admin;

-- UTENTE VAULT
CREATE USER vault_admin WITH PASSWORD 'vault_admin' CREATEROLE;
GRANT ALL PRIVILEGES ON DATABASE agritech_db TO vault_admin;
GRANT ALL PRIVILEGES ON SCHEMA agritech TO vault_admin;
ALTER USER vault_admin WITH SUPERUSER;

-- STORAGE PER HASHICORP VAULT
CREATE TABLE IF NOT EXISTS vault_kv_store
(
    parent_path TEXT COLLATE "C" NOT NULL,
    path        TEXT COLLATE "C",
    key         TEXT COLLATE "C",
    value       BYTEA,
    CONSTRAINT pkey PRIMARY KEY (path, key)
);

CREATE INDEX IF NOT EXISTS parent_path_idx ON vault_kv_store (parent_path);
GRANT ALL PRIVILEGES ON TABLE vault_kv_store TO vault_admin;

-- TRACCIAMENTO DELLE RICHIESTE DI REPORT
-- CREATE TABLE report_requests
-- (
--     id           UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
--     requested_by VARCHAR(100),
--     sensor_type  VARCHAR(50),
--     date_from    DATE,
--     date_to      DATE,
--     status       VARCHAR(20)      DEFAULT 'PENDING',
--     result       TEXT,
--     created_at   TIMESTAMP        DEFAULT NOW(),
--     completed_at TIMESTAMP
-- );