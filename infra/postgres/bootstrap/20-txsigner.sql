-- 261113-trading-real-bot U1 (infrastructure-specification.md "Armazenamento"): the own schema and logins of the
-- TxSigner on the local PostgreSQL. Runs as the SUPERUSER after 10-roles.sql, only when TXSIGNER_DB_OWNER_USER is set
-- (bootstrap.sh). Idempotent. The api and the worker get NO privilege on the schema txsigner (NFR1.2); the owner applies
-- the changelog of apps/txsigner (txsigner-migrate) and the application login receives only what that changelog grants.
\set ON_ERROR_STOP on
\set QUIET on
\getenv txsigner_owner TXSIGNER_DB_OWNER_USER
\getenv txsigner_owner_password TXSIGNER_DB_OWNER_PASSWORD
\getenv txsigner_app TXSIGNER_DB_USER
\getenv txsigner_app_password TXSIGNER_DB_PASSWORD

BEGIN;

SELECT format('CREATE ROLE %I LOGIN', r.name)
FROM (VALUES (:'txsigner_owner'), (:'txsigner_app')) AS r(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name) \gexec

SELECT format('ALTER ROLE %I WITH LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS '
              'PASSWORD %L', r.name, r.secret)
FROM (VALUES (:'txsigner_owner', :'txsigner_owner_password'),
             (:'txsigner_app', :'txsigner_app_password')) AS r(name, secret) \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), :'txsigner_owner', :'txsigner_app') \gexec

SELECT format('CREATE SCHEMA IF NOT EXISTS txsigner AUTHORIZATION %I', :'txsigner_owner') \gexec

-- Neither the TxSigner logins in the schema public nor the logins of the api in the schema txsigner.
SELECT format('REVOKE ALL ON SCHEMA public FROM %I, %I', :'txsigner_owner', :'txsigner_app') \gexec
REVOKE ALL ON SCHEMA txsigner FROM PUBLIC;

COMMIT;
