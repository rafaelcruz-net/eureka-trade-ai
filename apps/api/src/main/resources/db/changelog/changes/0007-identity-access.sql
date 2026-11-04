--liquibase formatted sql

-- U3 Invitations, accounts and sessions (entities.md, infrastructure-specification.md). Expand only: new tables, new
-- indexes, a named constraint and the seeded tenant "Beta"; no existing column is removed or changed, so the U4 image
-- keeps working on this schema (rollback of cicd-pipeline.md). Applied by db-migrate with the schema OWNER login.

--changeset memetrade:0007-invite
-- Single-use invitation (BR1.1 to BR1.4). Only the SHA-256 digest of the code and its last 4 characters are stored,
-- never the code (BR2.3, NFR6.2). EXPIRED is derived on read (BR1.2) and never stored.
CREATE TABLE invite (
    invite_id       uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (tenant_id),
    code_digest     char(64)    NOT NULL,
    code_suffix     varchar(4)  NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'PENDING',
    issued_by       varchar(64) NOT NULL,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz NULL,
    used_by_user_id uuid        NULL REFERENCES app_user (user_id),
    revoked_at      timestamptz NULL,
    CONSTRAINT uq_invite_code_digest UNIQUE (code_digest),
    CONSTRAINT uq_invite_used_by UNIQUE (used_by_user_id),
    CONSTRAINT invite_status_ck CHECK (status IN ('PENDING', 'USED', 'REVOKED')),
    CONSTRAINT invite_code_digest_ck CHECK (code_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT invite_code_suffix_ck CHECK (code_suffix ~ '^[A-Za-z0-9_-]{4}$'),
    CONSTRAINT invite_validity_ck CHECK (
        expires_at >= created_at + interval '1 hour' AND expires_at <= created_at + interval '30 days'),
    -- USED needs usedAt and usedByUserId; REVOKED needs revokedAt; PENDING has none of the three (entities.md).
    CONSTRAINT invite_state_dates_ck CHECK (
        (status = 'PENDING' AND used_at IS NULL AND used_by_user_id IS NULL AND revoked_at IS NULL)
        OR (status = 'USED' AND used_at IS NOT NULL AND used_by_user_id IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND used_at IS NULL AND used_by_user_id IS NULL))
);
-- NFR2.2 / NFR3.3: the administrator list pages by (created_at desc, invite_id) without OFFSET.
CREATE INDEX invite_created_idx ON invite (created_at DESC, invite_id);
--rollback DROP TABLE invite;

--changeset memetrade:0007-terms-acceptance
-- Acceptance of the terms and the risk notice, written once when the account is created (BR3.3, BR3.5).
CREATE TABLE terms_acceptance (
    acceptance_id uuid        PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES app_user (user_id),
    tenant_id     uuid        NOT NULL REFERENCES tenant (tenant_id),
    terms_version varchar(32) NOT NULL,
    accepted_at   timestamptz NOT NULL,
    invite_id     uuid        NOT NULL REFERENCES invite (invite_id),
    CONSTRAINT uq_terms_acceptance_invite UNIQUE (invite_id)
);
CREATE INDEX terms_acceptance_user_idx ON terms_acceptance (user_id, accepted_at DESC);
--rollback DROP TABLE terms_acceptance;

--changeset memetrade:0007-terms-acceptance-privileges splitStatements:false
-- Insert only for the application (entities.md TermsAcceptance): whatever the login names in .env, every non-owner
-- grantee loses UPDATE, DELETE and TRUNCATE, as U2 does for audit_event.
REVOKE UPDATE, DELETE, TRUNCATE ON terms_acceptance FROM PUBLIC;
DO $$
DECLARE
    grantee_name text;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'terms_acceptance'::regclass
          AND a.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
          AND a.grantee <> c.relowner
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON terms_acceptance FROM %I', grantee_name);
    END LOOP;
END;
$$;
--rollback SELECT 1;

--changeset memetrade:0007-wallet-address-constraint-name splitStatements:false
-- The unique constraint of wallet.address exists since U1 under the name PostgreSQL generated (wallet_address_key).
-- The account creation recognises its violation BY NAME (BR3.7, reliability-design.md), so it is renamed to
-- uq_wallet_address only when the old name exists and the new one does not: never a second unique constraint.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'wallet'::regclass AND conname = 'wallet_address_key')
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'wallet'::regclass AND conname = 'uq_wallet_address')
    THEN
        ALTER TABLE wallet RENAME CONSTRAINT wallet_address_key TO uq_wallet_address;
    END IF;
END;
$$;
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'wallet'::regclass AND conname = 'uq_wallet_address') THEN ALTER TABLE wallet RENAME CONSTRAINT uq_wallet_address TO wallet_address_key; END IF; END; $$;

--changeset memetrade:0007-app-user-role-status-index
-- NFR2.4: guest count of the identity counts (BETA_USER and ACTIVE) through an index.
CREATE INDEX app_user_role_status_idx ON app_user (role, status);
--rollback DROP INDEX app_user_role_status_idx;

--changeset memetrade:0007-tenant-beta
-- BR1.7 / NFR10.4: the tenant "Beta" with a fixed identifier, inserted only when absent, so running the seed again
-- neither duplicates nor changes it. No route creates, edits or suspends a tenant in the beta.
INSERT INTO tenant (tenant_id, name, status, created_at)
SELECT '00000000-0000-4000-8000-0000000000b3'::uuid, 'Beta', 'ACTIVE', now()
WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE tenant_id = '00000000-0000-4000-8000-0000000000b3'::uuid);
--rollback DELETE FROM tenant t WHERE t.tenant_id = '00000000-0000-4000-8000-0000000000b3'::uuid AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.tenant_id = t.tenant_id) AND NOT EXISTS (SELECT 1 FROM invite i WHERE i.tenant_id = t.tenant_id);
