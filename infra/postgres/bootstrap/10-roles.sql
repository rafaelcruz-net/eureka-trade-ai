-- Database role bootstrap of MemeTrade (U2 security-design.md, "Passo de preparo dos papéis").
--
-- Single owner of logins, ownership and default privileges. Runs as the PostgreSQL SUPERUSER on every
-- `docker compose up` (service db-bootstrap) and in the integration-test base, on a fresh volume or on a volume
-- created by U1 (where the application login owned the schema). Idempotent: running it twice changes nothing.
--
-- Logins (names and passwords from the environment, read with \getenv; never written to the repository):
--   DB_OWNER_USER      schema owner; used only by db-migrate (Liquibase)
--   DB_USER            application; data only, no DDL; on audit_event only SELECT and INSERT
--   DB_RETENTION_USER  member of the fixed group role audit_retention (SELECT and DELETE on audit_event only)
-- The group role audit_retention has a fixed name: the U2 migration and the audit_event trigger use it.
--
-- Usage: psql -v ON_ERROR_STOP=1 -f 10-roles.sql   (with the six DB_* variables in the environment)

\set ON_ERROR_STOP on
\set QUIET on
\getenv owner_user DB_OWNER_USER
\getenv owner_password DB_OWNER_PASSWORD
\getenv app_user DB_USER
\getenv app_password DB_PASSWORD
\getenv retention_user DB_RETENTION_USER
\getenv retention_password DB_RETENTION_PASSWORD

BEGIN;

-- 1. Logins and the group role. Created when missing; attributes and passwords refreshed on every run.
SELECT format('CREATE ROLE %I LOGIN', r.name)
FROM (VALUES (:'owner_user'), (:'app_user'), (:'retention_user')) AS r(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name) \gexec

SELECT format('ALTER ROLE %I WITH LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS '
              'PASSWORD %L', r.name, r.secret)
FROM (VALUES (:'owner_user', :'owner_password'),
             (:'app_user', :'app_password'),
             (:'retention_user', :'retention_password')) AS r(name, secret) \gexec

SELECT 'CREATE ROLE audit_retention NOLOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_retention') \gexec
ALTER ROLE audit_retention WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- 2. Only the retention login is a member of audit_retention; the owner and the application never are.
SELECT format('GRANT audit_retention TO %I', :'retention_user')
WHERE NOT pg_has_role(:'retention_user', 'audit_retention', 'MEMBER') \gexec

SELECT format('REVOKE audit_retention FROM %I', m.rolname)
FROM pg_auth_members am
JOIN pg_roles g ON g.oid = am.roleid AND g.rolname = 'audit_retention'
JOIN pg_roles m ON m.oid = am.member
WHERE m.rolname IN (:'owner_user', :'app_user') \gexec

-- Connections: nobody by default; the three logins explicitly.
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), :'owner_user', :'retention_user') \gexec

-- 3. Ownership moves from the application login to the owner (U1 volumes). On a fresh volume nothing moves.
SELECT EXISTS (
           SELECT 1 FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname = :'app_user'))
       OR EXISTS (
           SELECT 1 FROM pg_namespace WHERE nspowner = (SELECT oid FROM pg_roles WHERE rolname = :'app_user'))
       AS app_owned_objects \gset

SELECT format('REASSIGN OWNED BY %I TO %I', :'app_user', :'owner_user') \gexec
SELECT format('ALTER SCHEMA public OWNER TO %I', :'owner_user') \gexec

-- 4. No DDL for anybody but the owner; usage for the application and the retention group.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE CREATE ON SCHEMA public FROM %I, %I', :'app_user', :'retention_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I, audit_retention', :'app_user') \gexec

-- 5. Tables and sequences created later by the owner (every future Unit) are usable by the application.
--    A Unit that must restrict one of its tables revokes in its own migration (as U2 does for audit_event).
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
              'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'owner_user', :'app_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
              'GRANT USAGE, SELECT ON SEQUENCES TO %I', :'owner_user', :'app_user') \gexec

-- 6. Only when ownership was just transferred (U1 volume): the existing tables get the same privileges. Later
--    runs never re-grant, so the revocations of the migrations (audit_event) stay in force.
\if :app_owned_objects
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'app_user') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'app_user') \gexec
\endif

COMMIT;

\echo 'db-bootstrap: roles, ownership and default privileges are in place'
