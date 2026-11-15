--liquibase formatted sql

-- Own database of the TxSigner (entities.md: WalletPolicy, SignerUsedNonce, SigningRecord), in the schema "txsigner",
-- applied by the txsigner-migrate service with the owner login. The api and the worker have no login here (NFR1.2).
-- Instants are timestamptz (UTC); enums are upper case; lamports are bigint, never floating point (NFR10.1).

--changeset txsigner:0001-wallet-policy
CREATE TABLE wallet_policy (
    bot_wallet_address          varchar(44)  PRIMARY KEY,
    kms_key_ref                 varchar(256) NOT NULL UNIQUE,
    main_wallet_address         varchar(44)  NOT NULL,
    authorized_cap_lamports     bigint       NOT NULL,
    authorized_mode             varchar(16)  NOT NULL,
    closed                      boolean      NOT NULL DEFAULT false,
    blocked                     boolean      NOT NULL DEFAULT false,
    restored                    boolean      NOT NULL DEFAULT false,
    creation_request_id         uuid         NOT NULL,
    max_withdrawal_fee_lamports bigint       NOT NULL,
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    CONSTRAINT wallet_policy_creation_request_uk UNIQUE (creation_request_id),
    CONSTRAINT wallet_policy_bot_base58_ck CHECK (bot_wallet_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    CONSTRAINT wallet_policy_main_base58_ck CHECK (main_wallet_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    -- BR2.1: the cap never passes 10 SOL, enforced again by the database.
    CONSTRAINT wallet_policy_cap_ck CHECK (authorized_cap_lamports BETWEEN 0 AND 10000000000),
    CONSTRAINT wallet_policy_mode_ck CHECK (authorized_mode IN ('SIGNED', 'AUTO_EXITS', 'AUTO_FULL')),
    CONSTRAINT wallet_policy_fee_ck CHECK (max_withdrawal_fee_lamports BETWEEN 0 AND 100000000)
);
CREATE INDEX wallet_policy_created_idx ON wallet_policy (created_at);
--rollback DROP TABLE wallet_policy;

--changeset txsigner:0001-wallet-policy-anchor splitStatements:false
-- The main wallet, the key reference and the creation request never change after creation (BR4.1, ADR-010): a
-- compromised session of the TxSigner itself cannot redirect withdrawals by updating the row.
CREATE FUNCTION wallet_policy_keep_anchor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.main_wallet_address <> OLD.main_wallet_address
        OR NEW.kms_key_ref <> OLD.kms_key_ref
        OR NEW.creation_request_id <> OLD.creation_request_id
        OR NEW.bot_wallet_address <> OLD.bot_wallet_address THEN
        RAISE EXCEPTION 'wallet_policy anchor is immutable' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.blocked AND NOT NEW.blocked THEN
        RAISE EXCEPTION 'a blocked wallet_policy is unblocked only by the runbook' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER wallet_policy_keep_anchor_trg BEFORE UPDATE ON wallet_policy
    FOR EACH ROW EXECUTE FUNCTION wallet_policy_keep_anchor();
CREATE FUNCTION wallet_policy_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'wallet_policy rows are never deleted' USING ERRCODE = 'P0001';
END;
$$;
CREATE TRIGGER wallet_policy_no_delete_trg BEFORE DELETE ON wallet_policy
    FOR EACH ROW EXECUTE FUNCTION wallet_policy_no_delete();
--rollback DROP TRIGGER wallet_policy_no_delete_trg ON wallet_policy; DROP FUNCTION wallet_policy_no_delete(); DROP TRIGGER wallet_policy_keep_anchor_trg ON wallet_policy; DROP FUNCTION wallet_policy_keep_anchor();

--changeset txsigner:0001-signer-used-nonce
-- BR1.8: every nonce accepted is kept until its consent expired plus one day.
CREATE TABLE signer_used_nonce (
    nonce       varchar(64) PRIMARY KEY,
    purge_after timestamptz NOT NULL
);
CREATE INDEX signer_used_nonce_purge_idx ON signer_used_nonce (purge_after);
--rollback DROP TABLE signer_used_nonce;

--changeset txsigner:0001-policy-change
-- Applied changes of a policy, for the idempotency of the consents route by requestId (contract R1).
CREATE TABLE policy_change (
    request_id         uuid        PRIMARY KEY,
    bot_wallet_address varchar(44) NOT NULL REFERENCES wallet_policy (bot_wallet_address),
    kind               varchar(16) NOT NULL,
    cap_lamports       bigint      NOT NULL,
    mode               varchar(16) NOT NULL,
    consent_digest     char(64)    NULL,
    decided_at         timestamptz NOT NULL,
    CONSTRAINT policy_change_kind_ck CHECK (kind IN ('RAISE_MODE', 'RAISE_CAP', 'LOWER_MODE', 'LOWER_CAP', 'CLOSE')),
    CONSTRAINT policy_change_mode_ck CHECK (mode IN ('SIGNED', 'AUTO_EXITS', 'AUTO_FULL'))
);
CREATE INDEX policy_change_wallet_idx ON policy_change (bot_wallet_address, decided_at);
--rollback DROP TABLE policy_change;

--changeset txsigner:0001-signing-record splitStatements:false
-- BR5.2: one row per signing request, signed or refused; append-only. The signed transaction is kept for the replay
-- of the same request (contract R1); a refusal keeps only its reason code, never the transaction.
CREATE TABLE signing_record (
    signing_request_id uuid        PRIMARY KEY,
    bot_wallet_address varchar(44) NOT NULL,
    operation_kind     varchar(16) NOT NULL,
    message_digest     char(64)    NOT NULL,
    decision           varchar(8)  NOT NULL,
    refusal_code       varchar(40) NULL,
    signature          varchar(90) NULL,
    signed_transaction text        NULL,
    decided_at         timestamptz NOT NULL,
    CONSTRAINT signing_record_kind_ck CHECK (operation_kind IN ('WITHDRAWAL', 'SWAP_BUY', 'SWAP_SELL')),
    CONSTRAINT signing_record_decision_ck CHECK (decision IN ('SIGNED', 'REFUSED')),
    CONSTRAINT signing_record_shape_ck CHECK (
        (decision = 'SIGNED' AND signature IS NOT NULL AND signed_transaction IS NOT NULL AND refusal_code IS NULL)
        OR (decision = 'REFUSED' AND refusal_code IS NOT NULL AND signature IS NULL AND signed_transaction IS NULL))
);
CREATE INDEX signing_record_signed_idx ON signing_record (decided_at) WHERE decision = 'SIGNED';
CREATE FUNCTION signing_record_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'signing_record is append-only' USING ERRCODE = 'P0001';
END;
$$;
CREATE TRIGGER signing_record_append_only_trg BEFORE UPDATE OR DELETE ON signing_record
    FOR EACH ROW EXECUTE FUNCTION signing_record_append_only();
--rollback DROP TRIGGER signing_record_append_only_trg ON signing_record; DROP FUNCTION signing_record_append_only(); DROP TABLE signing_record;

--changeset txsigner:0001-recovery-run
-- NFR4.13: one row per run of the recovery mode; recovery_completed_at is written at the end (security-design.md).
CREATE TABLE recovery_run (
    run_id                uuid        PRIMARY KEY,
    started_at            timestamptz NOT NULL,
    recovery_completed_at timestamptz NULL,
    restored_count        integer     NOT NULL DEFAULT 0,
    blocked_count         integer     NOT NULL DEFAULT 0
);
--rollback DROP TABLE recovery_run;

--changeset txsigner:0001-shedlock
CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);
--rollback DROP TABLE shedlock;

--changeset txsigner:0001-grants splitStatements:false
-- Least privilege of the application login: no DELETE except on the nonces (purge) and nothing on the history tables
-- beyond INSERT and SELECT. The login itself is created by infra/postgres/bootstrap (TXSIGNER_DB_USER).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'txsigner_app') THEN
        GRANT USAGE ON SCHEMA txsigner TO txsigner_app;
        GRANT SELECT, INSERT, UPDATE ON wallet_policy TO txsigner_app;
        GRANT SELECT, INSERT, DELETE ON signer_used_nonce TO txsigner_app;
        GRANT SELECT, INSERT ON policy_change TO txsigner_app;
        GRANT SELECT, INSERT ON signing_record TO txsigner_app;
        GRANT SELECT, INSERT, UPDATE ON recovery_run TO txsigner_app;
        GRANT SELECT, INSERT, UPDATE ON shedlock TO txsigner_app;
    END IF;
END;
$$;
--rollback SELECT 1;
