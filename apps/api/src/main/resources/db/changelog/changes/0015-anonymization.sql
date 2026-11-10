--liquibase formatted sql

-- U5 u5-anonymization of 261109-termos-lgpd (infrastructure-specification.md, reliability-design.md NFR5.3): expand or
-- relax only. The account gains the final status ANONYMIZED, the wallet may lose its address and provider (only together
-- with anonymized_at), the login challenges get an index by wallet (NFR3.2) and the feedback of ONE person can be erased
-- through a SECURITY DEFINER function, while the trigger of 0011 stays as it is (BR5.8).
--
-- Rollback: every changeset declares one. The rollback that restores NOT NULL on wallet.address and wallet.provider is
-- valid ONLY BEFORE THE FIRST ANONYMIZATION: once an account was anonymized the rollback fails on the null rows, and the
-- only way is a forward fix (cicd-pipeline.md). AnonymizationMigrationIT runs every rollback on a base without
-- anonymized accounts.
--
-- Privileges: the function belongs to the schema owner, which db-bootstrap (infra/postgres/bootstrap/10-roles.sql)
-- makes a member of feedback_retention; the migration grants no role (the owner is NOCREATEROLE). EXECUTE is revoked from
-- PUBLIC and granted only to the application login, found as the non-owner grantee of INSERT on feedback_entry (its name
-- comes from .env, as in 0005).

--changeset memetrade:0015-app-user-status-anonymized
-- entities.md Account: ANONYMIZED is final (no login, refresh or new session, BR5.7).
ALTER TABLE app_user DROP CONSTRAINT app_user_status_ck;
ALTER TABLE app_user ADD CONSTRAINT app_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED', 'ANONYMIZED'));
--rollback ALTER TABLE app_user DROP CONSTRAINT app_user_status_ck; ALTER TABLE app_user ADD CONSTRAINT app_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED'));

--changeset memetrade:0015-wallet-anonymizable
-- entities.md Wallet (BR5.2, BR5.4): the row stays (the account references it); address and provider become nullable
-- and anonymized_at records when they were removed. The previous image never writes null here.
-- The rollback restores NOT NULL and is valid only before the first anonymization.
ALTER TABLE wallet ALTER COLUMN address DROP NOT NULL;
ALTER TABLE wallet ALTER COLUMN provider DROP NOT NULL;
ALTER TABLE wallet ADD COLUMN anonymized_at timestamptz NULL;
--rollback ALTER TABLE wallet DROP COLUMN anonymized_at; ALTER TABLE wallet ALTER COLUMN provider SET NOT NULL; ALTER TABLE wallet ALTER COLUMN address SET NOT NULL;

--changeset memetrade:0015-wallet-anonymized-check
-- Address and provider are absent together, and exactly when anonymized_at is set.
ALTER TABLE wallet ADD CONSTRAINT wallet_anonymized_ck CHECK (
    (anonymized_at IS NULL) = (address IS NOT NULL) AND (address IS NULL) = (provider IS NULL));
--rollback ALTER TABLE wallet DROP CONSTRAINT wallet_anonymized_ck;

--changeset memetrade:0015-wallet-address-base58-nullable
-- The Base58 format still holds whenever the address exists.
ALTER TABLE wallet DROP CONSTRAINT wallet_address_base58_ck;
ALTER TABLE wallet ADD CONSTRAINT wallet_address_base58_ck CHECK (
    address IS NULL OR address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');
--rollback ALTER TABLE wallet DROP CONSTRAINT wallet_address_base58_ck; ALTER TABLE wallet ADD CONSTRAINT wallet_address_base58_ck CHECK (address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');

--changeset memetrade:0015-login-challenge-wallet-idx
-- NFR3.2: the anonymization deletes the login challenges of the address through this index (0001 indexes only nonce
-- and expires_at).
CREATE INDEX login_challenge_wallet_idx ON login_challenge (wallet_address);
--rollback DROP INDEX login_challenge_wallet_idx;

--changeset memetrade:0015-erase-feedback-of-user splitStatements:false
-- BR5.8, NFR2.3: the ONLY way the application deletes feedback. SECURITY DEFINER runs the DELETE as the owner, a member
-- of feedback_retention, so the unchanged trigger feedback_entry_guard accepts it; a direct DELETE of the application
-- login is still refused. The search_path is fixed and the body deletes only the rows of that person in that tenant.
CREATE OR REPLACE FUNCTION erase_feedback_of_user(p_tenant uuid, p_user uuid) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    erased integer;
BEGIN
    IF p_tenant IS NULL OR p_user IS NULL THEN
        RAISE EXCEPTION 'erase_feedback_of_user needs the tenant and the person'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    DELETE FROM public.feedback_entry WHERE tenant_id = p_tenant AND user_id = p_user;
    GET DIAGNOSTICS erased = ROW_COUNT;
    RETURN erased;
END;
$$;
REVOKE ALL ON FUNCTION erase_feedback_of_user(uuid, uuid) FROM PUBLIC;
DO $$
DECLARE
    grantee_name text;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'feedback_entry'::regclass
          AND a.privilege_type = 'INSERT'
          AND a.grantee <> c.relowner
    LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION erase_feedback_of_user(uuid, uuid) TO %I', grantee_name);
    END LOOP;
END;
$$;
--rollback DROP FUNCTION erase_feedback_of_user(uuid, uuid);
