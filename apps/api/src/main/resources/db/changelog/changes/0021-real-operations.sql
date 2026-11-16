--liquibase formatted sql

-- 261113-trading-real-bot, U5 u5-real-operations (infrastructure-specification.md "Banco: migração 0021, só de
-- expansão", security-design.md "Redação da trilha", scalability-design.md, U5-NFR3.8, U5-NFR3.9, U5-NFR8.6). Expand
-- only: no column is removed; the images of the U4 keep working on this schema (the release state has a DEFAULT and a
-- trigger keeps it in step with revoked_at). Every changeset declares its rollback; the rollback of the withdrawal CHECK
-- stops when a withdrawal was already redacted (the runbook keeps the new CHECK and rolls back only the images).
--
-- Privileges follow the matrix of 0018 and 0019: the default privileges of db-bootstrap give the application login
-- SELECT, INSERT, UPDATE and DELETE on every new table; every non-owner grantee other than risk_retention loses every
-- privilege and the INSERT holders (the application, whose name comes from .env) get back exactly the set of the matrix.
-- The retention login deletes through risk_retention only, and the age triggers check the age again. Lamports and base
-- units are bigint or numeric, never floating point; instants are timestamptz (UTC).

--changeset memetrade:0021-real-release-no-duplicates
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM (SELECT user_id, network FROM real_release WHERE revoked_at IS NULL GROUP BY user_id, network HAVING count(*) > 1) duplicates
-- BR1.2: at most one release not revoked per person and network. A duplicate stops the whole migration before any
-- change; the runbook revokes the oldest one through the route of the administrator and runs it again.
SELECT 1;
--rollback SELECT 1;

--changeset memetrade:0021-real-release-state splitStatements:false
-- BR1.1 to BR1.6: the state of a release with its gates. The backfill keeps the meaning of the U1: revoked, already in
-- force (ACTIVE), or still in its 24 hours (QUARANTINE). The previous images write rows without a state (DEFAULT) and
-- revoke by setting revoked_at, which the trigger turns into REVOKED; a REVOKED release never comes back.
ALTER TABLE real_release ADD COLUMN state varchar(16) NOT NULL DEFAULT 'QUARANTINE';
ALTER TABLE real_release ADD COLUMN blocked_reason varchar(24) NULL;
ALTER TABLE real_release ADD COLUMN gates_checked_at timestamptz NULL;
ALTER TABLE real_release ADD COLUMN revoked_by uuid NULL;
UPDATE real_release SET state = CASE
    WHEN revoked_at IS NOT NULL THEN 'REVOKED'
    WHEN effective_at <= now() THEN 'ACTIVE'
    ELSE 'QUARANTINE' END;
ALTER TABLE real_release ADD CONSTRAINT real_release_state_ck CHECK (state IN ('QUARANTINE', 'ACTIVE', 'BLOCKED',
    'REVOKED'));
ALTER TABLE real_release ADD CONSTRAINT real_release_blocked_ck CHECK (state = 'REVOKED'
    OR (state = 'BLOCKED' AND blocked_reason IS NOT NULL) OR (state IN ('QUARANTINE', 'ACTIVE') AND blocked_reason IS NULL));
ALTER TABLE real_release ADD CONSTRAINT real_release_blocked_value_ck CHECK (blocked_reason IS NULL OR blocked_reason IN
    ('CI_RED', 'CI_UNREACHABLE', 'DIVERGENCE_OPEN', 'KILL_SWITCH_ON', 'NETWORK_MISMATCH'));
ALTER TABLE real_release ADD CONSTRAINT real_release_revoked_ck CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL));
-- BR1.2 (U5-NFR4.1): one release not revoked per person and network. It reads revoked_at, never state, so an image of
-- the U4 is never stuck on it.
CREATE UNIQUE INDEX uq_real_release_open ON real_release (user_id, network) WHERE revoked_at IS NULL;
-- U5-NFR3.8: the gates job reads the due releases by state and instant.
CREATE INDEX real_release_state_effective_idx ON real_release (state, effective_at);

CREATE OR REPLACE FUNCTION real_release_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'REVOKED' AND (NEW.state <> 'REVOKED' OR NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
        RAISE EXCEPTION 'real_release: a revoked release is final' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.release_id <> OLD.release_id OR NEW.user_id <> OLD.user_id OR NEW.network <> OLD.network
       OR NEW.requested_at <> OLD.requested_at OR NEW.effective_at <> OLD.effective_at THEN
        RAISE EXCEPTION 'real_release: the identity of a release never changes' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
        NEW.state := 'REVOKED';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER real_release_guard BEFORE UPDATE ON real_release FOR EACH ROW EXECUTE FUNCTION real_release_guard();
--rollback DROP TRIGGER real_release_guard ON real_release;
--rollback DROP FUNCTION real_release_guard();
--rollback DROP INDEX real_release_state_effective_idx;
--rollback DROP INDEX uq_real_release_open;
--rollback ALTER TABLE real_release DROP COLUMN revoked_by, DROP COLUMN gates_checked_at, DROP COLUMN blocked_reason, DROP COLUMN state;

--changeset memetrade:0021-real-release-privileges splitStatements:false
-- U5-NFR3.9: a release is kept 5 years after its revocation and deleted only by the retention login; the application
-- keeps SELECT, INSERT and UPDATE and loses DELETE and TRUNCATE.
CREATE OR REPLACE FUNCTION real_ops_age_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER') THEN
        RAISE EXCEPTION '%: DELETE is allowed only to the risk retention role', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (to_jsonb(OLD) ->> TG_ARGV[0]) IS NULL
       OR (to_jsonb(OLD) ->> TG_ARGV[0])::timestamptz >= now() - TG_ARGV[1]::interval THEN
        RAISE EXCEPTION '%: only rows older than % are deleted', TG_TABLE_NAME, TG_ARGV[1]
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER real_release_age BEFORE DELETE ON real_release
    FOR EACH ROW EXECUTE FUNCTION real_ops_age_guard('revoked_at', '5 years');
DO $$
DECLARE
    grantee_name text;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'real_release'::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner
          AND r.rolname <> 'risk_retention'
    LOOP
        EXECUTE format('REVOKE DELETE, TRUNCATE ON real_release FROM %I', grantee_name);
    END LOOP;
END;
$$;
GRANT SELECT, DELETE ON real_release TO risk_retention;
--rollback REVOKE SELECT, DELETE ON real_release FROM risk_retention;
--rollback DROP TRIGGER real_release_age ON real_release;
--rollback DROP FUNCTION real_ops_age_guard();

--changeset memetrade:0021-real-operations-tables splitStatements:false
-- entities.md ReconciliationRun, Divergence, RealKillSwitch, PendingTxSignerCommand, RestoreMarker.
CREATE TABLE real_reconciliation_run (
    run_id            uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id     uuid        NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    trigger           varchar(20) NOT NULL,
    slot              bigint      NULL,
    chain_lamports    bigint      NULL,
    ledger_lamports   bigint      NULL,
    token_diffs       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    in_transit_orders integer     NOT NULL DEFAULT 0,
    result            varchar(12) NOT NULL,
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz NOT NULL,
    CONSTRAINT real_reconciliation_run_trigger_ck CHECK (trigger IN ('ORDER_CONFIRMED', 'SCHEDULE',
        'AFTER_COMPENSATION', 'AFTER_RESTORE')),
    CONSTRAINT real_reconciliation_run_result_ck CHECK (result IN ('CLEAN', 'DIVERGENT', 'DEFERRED', 'UNAVAILABLE')),
    -- BR2.1: a compared wallet carries the finalized slot and both balances.
    CONSTRAINT real_reconciliation_run_measure_ck CHECK (result NOT IN ('CLEAN', 'DIVERGENT')
        OR (slot IS NOT NULL AND chain_lamports IS NOT NULL AND ledger_lamports IS NOT NULL)),
    CONSTRAINT real_reconciliation_run_diffs_ck CHECK (jsonb_typeof(token_diffs) = 'array'),
    CONSTRAINT real_reconciliation_run_transit_ck CHECK (in_transit_orders >= 0)
);
-- U5-NFR3.8: the runs of a wallet, newest first; the purge by age.
CREATE INDEX real_reconciliation_run_wallet_idx ON real_reconciliation_run (bot_wallet_id, started_at DESC);
CREATE INDEX real_reconciliation_run_purge_idx ON real_reconciliation_run (started_at);

CREATE TABLE real_divergence (
    divergence_id          uuid         PRIMARY KEY,
    run_id                 uuid         NOT NULL REFERENCES real_reconciliation_run (run_id) ON DELETE RESTRICT,
    last_run_id            uuid         NOT NULL REFERENCES real_reconciliation_run (run_id) ON DELETE RESTRICT,
    tenant_id              uuid         NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id          uuid         NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    lamports_diff          bigint       NOT NULL,
    token_diffs            jsonb        NOT NULL DEFAULT '[]'::jsonb,
    state                  varchar(12)  NOT NULL DEFAULT 'OPEN',
    compensation_entry_ids jsonb        NOT NULL DEFAULT '[]'::jsonb,
    compensated_by         uuid         NULL,
    compensated_at         timestamptz  NULL,
    reason                 varchar(500) NULL,
    opened_at              timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    resolved_at            timestamptz  NULL,
    CONSTRAINT real_divergence_state_ck CHECK (state IN ('OPEN', 'COMPENSATED', 'RESOLVED')),
    CONSTRAINT real_divergence_resolved_ck CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT real_divergence_diffs_ck CHECK (jsonb_typeof(token_diffs) = 'array'
        AND jsonb_typeof(compensation_entry_ids) = 'array')
);
-- U5-NFR3.8 (entities.md): at most one divergence OPEN or COMPENSATED per wallet.
CREATE UNIQUE INDEX uq_real_divergence_open ON real_divergence (bot_wallet_id) WHERE state IN ('OPEN', 'COMPENSATED');
CREATE INDEX real_divergence_state_idx ON real_divergence (state, opened_at);
CREATE INDEX real_divergence_last_run_idx ON real_divergence (last_run_id);
CREATE INDEX real_divergence_run_idx ON real_divergence (run_id);

-- BR3.1, BR3.2: the one row of the kill switch REAL, changed by compare-and-set on its version.
CREATE TABLE real_kill_switch (
    switch_id    smallint     PRIMARY KEY DEFAULT 1,
    active       boolean      NOT NULL DEFAULT false,
    activated_at timestamptz  NULL,
    activated_by uuid         NULL,
    reason       varchar(500) NULL,
    version      bigint       NOT NULL DEFAULT 0,
    updated_at   timestamptz  NOT NULL,
    updated_by   uuid         NULL,
    CONSTRAINT real_kill_switch_single_ck CHECK (switch_id = 1),
    CONSTRAINT real_kill_switch_active_ck CHECK (NOT active OR (activated_at IS NOT NULL AND activated_by IS NOT NULL)),
    CONSTRAINT real_kill_switch_version_ck CHECK (version >= 0)
);
INSERT INTO real_kill_switch (switch_id, active, version, updated_at) VALUES (1, false, 0, now());

-- BR4.1, BR6.2: a command to the TxSigner not yet confirmed, sent again every minute with the same commandId. The
-- payload carries only ids and values, never an address (the CHECK refuses any run of 32 Base58 characters).
CREATE TABLE real_pending_txsigner_command (
    command_id      uuid        PRIMARY KEY,
    bot_wallet_id   uuid        NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    kind            varchar(16) NOT NULL,
    payload         jsonb       NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    last_attempt_at timestamptz NULL,
    confirmed_at    timestamptz NULL,
    CONSTRAINT real_pending_txsigner_command_kind_ck CHECK (kind IN ('LOWER_CAP', 'DEACTIVATE')),
    CONSTRAINT real_pending_txsigner_command_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT real_pending_txsigner_command_no_address_ck CHECK (payload::text !~ '[1-9A-HJ-NP-Za-km-z]{32,}')
);
-- U5-NFR3.8: the pending commands, oldest first.
CREATE INDEX real_pending_txsigner_command_pending_idx ON real_pending_txsigner_command (confirmed_at, created_at);

-- BR2.5: the restore check of the worker. One open marker at a time: CHECK_PENDING until the check finished, RESTORE
-- when the network had signatures of a bot wallet the database did not know, until the administrator confirms.
CREATE TABLE real_restore_marker (
    marker_id          uuid        PRIMARY KEY,
    phase              varchar(16) NOT NULL,
    opened_at          timestamptz NOT NULL,
    detected_at        timestamptz NULL,
    unknown_signatures integer     NOT NULL DEFAULT 0,
    confirmed_by       uuid        NULL,
    cleared_at         timestamptz NULL,
    CONSTRAINT real_restore_marker_phase_ck CHECK (phase IN ('CHECK_PENDING', 'RESTORE')),
    CONSTRAINT real_restore_marker_detected_ck CHECK ((phase = 'RESTORE') = (detected_at IS NOT NULL)),
    CONSTRAINT real_restore_marker_unknown_ck CHECK (unknown_signatures >= 0)
);
CREATE UNIQUE INDEX uq_real_restore_marker_open ON real_restore_marker ((true)) WHERE cleared_at IS NULL;
--rollback DROP TABLE real_restore_marker;
--rollback DROP TABLE real_pending_txsigner_command;
--rollback DROP TABLE real_kill_switch;
--rollback DROP TABLE real_divergence;
--rollback DROP TABLE real_reconciliation_run;

--changeset memetrade:0021-real-operations-triggers splitStatements:false
-- infrastructure-specification.md: for EVERY login (not SECURITY DEFINER). A run is insert-only; a divergence moves only
-- OPEN -> OPEN (new measure), OPEN -> COMPENSATED, COMPENSATED -> RESOLVED or OPEN, and keeps its identity; a command
-- changes only its attempts and its confirmation; the kill switch row and the marker keep their identity. Age: a clean
-- run and a confirmed command after 13 months, a divergence after 5 years (its runs stay while it exists).
CREATE OR REPLACE FUNCTION real_ops_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%: % is not allowed', TG_TABLE_NAME, TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE OR REPLACE FUNCTION real_divergence_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.divergence_id <> OLD.divergence_id OR NEW.run_id <> OLD.run_id OR NEW.tenant_id <> OLD.tenant_id
       OR NEW.bot_wallet_id <> OLD.bot_wallet_id OR NEW.opened_at <> OLD.opened_at THEN
        RAISE EXCEPTION 'real_divergence: the identity of a divergence never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT (OLD.state || '>' || NEW.state) = ANY (ARRAY['OPEN>OPEN', 'OPEN>COMPENSATED', 'COMPENSATED>RESOLVED',
            'COMPENSATED>OPEN']) THEN
        RAISE EXCEPTION 'real_divergence: % to % is not a transition', OLD.state, NEW.state
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION real_pending_txsigner_command_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'attempts' - 'last_attempt_at' - 'confirmed_at')
       <> (to_jsonb(OLD) - 'attempts' - 'last_attempt_at' - 'confirmed_at')
       OR (OLD.confirmed_at IS NOT NULL AND NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at) THEN
        RAISE EXCEPTION 'real_pending_txsigner_command: only the attempts and the confirmation change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION real_restore_marker_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.marker_id <> OLD.marker_id OR NEW.opened_at <> OLD.opened_at OR OLD.cleared_at IS NOT NULL THEN
        RAISE EXCEPTION 'real_restore_marker: a cleared marker never changes' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER real_reconciliation_run_no_update BEFORE UPDATE ON real_reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION real_ops_reject_change();
CREATE TRIGGER real_reconciliation_run_age BEFORE DELETE ON real_reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION real_ops_age_guard('started_at', '13 months');
CREATE TRIGGER real_reconciliation_run_no_truncate BEFORE TRUNCATE ON real_reconciliation_run
    FOR EACH STATEMENT EXECUTE FUNCTION real_ops_reject_change();
CREATE TRIGGER real_divergence_guard BEFORE UPDATE ON real_divergence
    FOR EACH ROW EXECUTE FUNCTION real_divergence_guard();
CREATE TRIGGER real_divergence_age BEFORE DELETE ON real_divergence
    FOR EACH ROW EXECUTE FUNCTION real_ops_age_guard('resolved_at', '5 years');
CREATE TRIGGER real_divergence_no_truncate BEFORE TRUNCATE ON real_divergence
    FOR EACH STATEMENT EXECUTE FUNCTION real_ops_reject_change();
CREATE TRIGGER real_pending_txsigner_command_guard BEFORE UPDATE ON real_pending_txsigner_command
    FOR EACH ROW EXECUTE FUNCTION real_pending_txsigner_command_guard();
CREATE TRIGGER real_pending_txsigner_command_age BEFORE DELETE ON real_pending_txsigner_command
    FOR EACH ROW EXECUTE FUNCTION real_ops_age_guard('confirmed_at', '13 months');
CREATE TRIGGER real_pending_txsigner_command_no_truncate BEFORE TRUNCATE ON real_pending_txsigner_command
    FOR EACH STATEMENT EXECUTE FUNCTION real_ops_reject_change();
CREATE TRIGGER real_restore_marker_guard BEFORE UPDATE ON real_restore_marker
    FOR EACH ROW EXECUTE FUNCTION real_restore_marker_guard();
CREATE TRIGGER real_restore_marker_no_delete BEFORE DELETE OR TRUNCATE ON real_restore_marker
    FOR EACH STATEMENT EXECUTE FUNCTION real_ops_reject_change();
CREATE TRIGGER real_kill_switch_no_delete BEFORE DELETE OR TRUNCATE ON real_kill_switch
    FOR EACH STATEMENT EXECUTE FUNCTION real_ops_reject_change();
--rollback DROP TRIGGER real_kill_switch_no_delete ON real_kill_switch;
--rollback DROP TRIGGER real_restore_marker_no_delete ON real_restore_marker;
--rollback DROP TRIGGER real_restore_marker_guard ON real_restore_marker;
--rollback DROP TRIGGER real_pending_txsigner_command_no_truncate ON real_pending_txsigner_command;
--rollback DROP TRIGGER real_pending_txsigner_command_age ON real_pending_txsigner_command;
--rollback DROP TRIGGER real_pending_txsigner_command_guard ON real_pending_txsigner_command;
--rollback DROP TRIGGER real_divergence_no_truncate ON real_divergence;
--rollback DROP TRIGGER real_divergence_age ON real_divergence;
--rollback DROP TRIGGER real_divergence_guard ON real_divergence;
--rollback DROP TRIGGER real_reconciliation_run_no_truncate ON real_reconciliation_run;
--rollback DROP TRIGGER real_reconciliation_run_age ON real_reconciliation_run;
--rollback DROP TRIGGER real_reconciliation_run_no_update ON real_reconciliation_run;
--rollback DROP FUNCTION real_restore_marker_guard();
--rollback DROP FUNCTION real_pending_txsigner_command_guard();
--rollback DROP FUNCTION real_divergence_guard();
--rollback DROP FUNCTION real_ops_reject_change();

--changeset memetrade:0021-real-operations-privileges splitStatements:false
-- The matrix of privileges (infrastructure-specification.md, as 0018 and 0019).
REVOKE ALL ON real_reconciliation_run, real_divergence, real_kill_switch, real_pending_txsigner_command,
    real_restore_marker FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('real_reconciliation_run', 'SELECT, INSERT'),
            ('real_divergence', 'SELECT, INSERT, UPDATE'),
            ('real_kill_switch', 'SELECT, UPDATE'),
            ('real_pending_txsigner_command', 'SELECT, INSERT, UPDATE'),
            ('real_restore_marker', 'SELECT, INSERT, UPDATE')
        ) AS s(table_name, app_privileges)
    LOOP
        SELECT array_agg(DISTINCT r.rolname) INTO writers
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = spec.table_name::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner;

        FOR grantee_name IN
            SELECT DISTINCT r.rolname
            FROM pg_class c
            CROSS JOIN LATERAL aclexplode(c.relacl) a
            JOIN pg_roles r ON r.oid = a.grantee
            WHERE c.oid = spec.table_name::regclass AND a.grantee <> c.relowner AND r.rolname <> 'risk_retention'
        LOOP
            EXECUTE format('REVOKE ALL ON %I FROM %I', spec.table_name, grantee_name);
            IF writers IS NOT NULL AND grantee_name = ANY (writers) THEN
                EXECUTE format('GRANT %s ON %I TO %I', spec.app_privileges, spec.table_name, grantee_name);
            END IF;
        END LOOP;
        EXECUTE format('REVOKE TRUNCATE ON %I FROM CURRENT_USER', spec.table_name);
    END LOOP;
END;
$$;
GRANT SELECT, DELETE ON real_reconciliation_run TO risk_retention;
GRANT SELECT, DELETE ON real_divergence TO risk_retention;
GRANT SELECT, DELETE ON real_pending_txsigner_command TO risk_retention;
--rollback REVOKE SELECT, DELETE ON real_pending_txsigner_command FROM risk_retention;
--rollback REVOKE SELECT, DELETE ON real_divergence FROM risk_retention;
--rollback REVOKE SELECT, DELETE ON real_reconciliation_run FROM risk_retention;

--changeset memetrade:0021-bot-wallet-addresses-nullable
-- BR6.2: the anonymization clears the addresses of a CLOSED wallet (the bot address once the TxSigner confirmed the
-- DEACTIVATE). The UNIQUE stays for the values present; the Base58 checks accept NULL; an ACTIVE wallet keeps both.
-- The rollback restores NOT NULL and is valid only before the first anonymization of a bot wallet.
ALTER TABLE bot_wallet_account ALTER COLUMN main_wallet_address DROP NOT NULL;
ALTER TABLE bot_wallet_account ALTER COLUMN public_address DROP NOT NULL;
ALTER TABLE bot_wallet_account DROP CONSTRAINT bot_wallet_account_address_ck;
ALTER TABLE bot_wallet_account ADD CONSTRAINT bot_wallet_account_address_ck CHECK (
    public_address IS NULL OR public_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');
ALTER TABLE bot_wallet_account DROP CONSTRAINT bot_wallet_account_main_ck;
ALTER TABLE bot_wallet_account ADD CONSTRAINT bot_wallet_account_main_ck CHECK (
    main_wallet_address IS NULL OR main_wallet_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');
ALTER TABLE bot_wallet_account ADD CONSTRAINT bot_wallet_account_active_addresses_ck CHECK (
    status <> 'ACTIVE' OR (public_address IS NOT NULL AND main_wallet_address IS NOT NULL));
ALTER TABLE consent_challenge ALTER COLUMN message DROP NOT NULL;
--rollback ALTER TABLE consent_challenge ALTER COLUMN message SET NOT NULL;
--rollback ALTER TABLE bot_wallet_account DROP CONSTRAINT bot_wallet_account_active_addresses_ck;
--rollback ALTER TABLE bot_wallet_account DROP CONSTRAINT bot_wallet_account_main_ck;
--rollback ALTER TABLE bot_wallet_account ADD CONSTRAINT bot_wallet_account_main_ck CHECK (main_wallet_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');
--rollback ALTER TABLE bot_wallet_account DROP CONSTRAINT bot_wallet_account_address_ck;
--rollback ALTER TABLE bot_wallet_account ADD CONSTRAINT bot_wallet_account_address_ck CHECK (public_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$');
--rollback ALTER TABLE bot_wallet_account ALTER COLUMN public_address SET NOT NULL;
--rollback ALTER TABLE bot_wallet_account ALTER COLUMN main_wallet_address SET NOT NULL;

--changeset memetrade:0021-withdrawal-redaction splitStatements:false rollbackSplitStatements:false
-- BR6.2: the anonymization clears the signed transaction and its digest of every withdrawal of the person (the
-- transaction holds the main wallet) and records redacted_at. From SIGNED on the CHECK of 0016 now accepts a redacted
-- row. The rollback restores the CHECK of 0016 only when no row was redacted; otherwise it stops (the runbook keeps
-- this CHECK and rolls back only the images, which work with it).
ALTER TABLE withdrawal ADD COLUMN redacted_at timestamptz NULL;
ALTER TABLE withdrawal DROP CONSTRAINT withdrawal_signed_ck;
ALTER TABLE withdrawal ADD CONSTRAINT withdrawal_signed_ck CHECK (
    state IN ('REQUESTED', 'FAILED') OR (signed_transaction IS NOT NULL AND message_digest IS NOT NULL)
    OR redacted_at IS NOT NULL);
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM withdrawal WHERE redacted_at IS NOT NULL) THEN RAISE EXCEPTION 'withdrawal rows were redacted: keep the CHECK of 0021 and roll back only the images (docs/runbooks/real-operations.md)'; END IF; END $$;
--rollback ALTER TABLE withdrawal DROP CONSTRAINT withdrawal_signed_ck;
--rollback ALTER TABLE withdrawal ADD CONSTRAINT withdrawal_signed_ck CHECK (state IN ('REQUESTED', 'FAILED') OR (signed_transaction IS NOT NULL AND message_digest IS NOT NULL));
--rollback ALTER TABLE withdrawal DROP COLUMN redacted_at;

--changeset memetrade:0021-widened-enums
-- BR2.5: the global pauses RESTORE and RESTORE_CHECK_PENDING (PauseReason) and the details PAUSED_RESTORE and
-- PAUSED_RESTORE_CHECK_PENDING of the barrier (the detail CHECK of 0018; the failure codes of 0019 need nothing new: the
-- recheck cancels a restore pause as PAUSED). BR7.1: decision.mode accepts REAL; the rows of PAPER do not change.
ALTER TABLE real_preferences DROP CONSTRAINT real_preferences_paused_ck;
ALTER TABLE real_preferences ADD CONSTRAINT real_preferences_paused_ck CHECK (paused_reasons <@ ARRAY['USER',
    'DIVERGENCE', 'DAILY_LOSS', 'KILL_SWITCH', 'ADMIN', 'UPSTREAM', 'RESTORE', 'RESTORE_CHECK_PENDING']::text[]);
ALTER TABLE real_risk_evaluation DROP CONSTRAINT real_risk_evaluation_detail_value_ck;
ALTER TABLE real_risk_evaluation ADD CONSTRAINT real_risk_evaluation_detail_value_ck CHECK (detail IS NULL OR detail IN
    ('NONE', 'NOT_RELEASED', 'REAL_DISABLED', 'NO_BOT_WALLET', 'PAUSED_USER', 'PAUSED_DAILY_LOSS', 'PAUSED_ADMIN',
    'PAUSED_DIVERGENCE', 'PAUSED_KILL_SWITCH', 'PAUSED_UPSTREAM', 'PAUSED_RESTORE', 'PAUSED_RESTORE_CHECK_PENDING',
    'NO_ASSESSMENT', 'BLOCKED', 'SIGNAL_STALE', 'SIGNAL_UNKNOWN', 'POSITION_PRICE_UNKNOWN', 'ORDER_SIZE',
    'DAILY_ORDER_COUNT', 'DAILY_LOSS', 'CAP', 'FREE_BALANCE'));
ALTER TABLE decision DROP CONSTRAINT decision_mode_ck;
ALTER TABLE decision ADD CONSTRAINT decision_mode_ck CHECK (mode IN ('PAPER', 'REAL'));
--rollback ALTER TABLE decision DROP CONSTRAINT decision_mode_ck;
--rollback ALTER TABLE decision ADD CONSTRAINT decision_mode_ck CHECK (mode = 'PAPER');
--rollback ALTER TABLE real_risk_evaluation DROP CONSTRAINT real_risk_evaluation_detail_value_ck;
--rollback ALTER TABLE real_risk_evaluation ADD CONSTRAINT real_risk_evaluation_detail_value_ck CHECK (detail IS NULL OR detail IN ('NONE', 'NOT_RELEASED', 'REAL_DISABLED', 'NO_BOT_WALLET', 'PAUSED_USER', 'PAUSED_DAILY_LOSS', 'PAUSED_ADMIN', 'PAUSED_DIVERGENCE', 'PAUSED_KILL_SWITCH', 'PAUSED_UPSTREAM', 'NO_ASSESSMENT', 'BLOCKED', 'SIGNAL_STALE', 'SIGNAL_UNKNOWN', 'POSITION_PRICE_UNKNOWN', 'ORDER_SIZE', 'DAILY_ORDER_COUNT', 'DAILY_LOSS', 'CAP', 'FREE_BALANCE'));
--rollback ALTER TABLE real_preferences DROP CONSTRAINT real_preferences_paused_ck;
--rollback ALTER TABLE real_preferences ADD CONSTRAINT real_preferences_paused_ck CHECK (paused_reasons <@ ARRAY['USER', 'DIVERGENCE', 'DAILY_LOSS', 'KILL_SWITCH', 'ADMIN', 'UPSTREAM']::text[]);

--changeset memetrade:0021-compensation-of-a-token
-- BR2.3: one COMPENSATION per divergent asset: SOL in lamports (no mint), or a mint in base units with its sign and zero
-- lamports. The other kinds keep the sign convention of 0016.
ALTER TABLE real_ledger_entry DROP CONSTRAINT real_ledger_entry_sign_ck;
ALTER TABLE real_ledger_entry ADD CONSTRAINT real_ledger_entry_sign_ck CHECK (
    (entry_kind = 'DEPOSIT' AND lamports > 0)
    OR (entry_kind IN ('WITHDRAWAL', 'NETWORK_FEE', 'RENT') AND lamports < 0)
    OR (entry_kind IN ('RESERVE', 'RESERVE_RELEASE') AND lamports > 0)
    OR (entry_kind IN ('SWAP_FILL_IN', 'SWAP_FILL_OUT') AND lamports <> 0)
    OR (entry_kind = 'COMPENSATION' AND mint IS NULL AND token_base_units IS NULL AND lamports <> 0)
    OR (entry_kind = 'COMPENSATION' AND mint IS NOT NULL AND token_base_units IS NOT NULL AND token_base_units <> 0
        AND lamports = 0));
--rollback ALTER TABLE real_ledger_entry DROP CONSTRAINT real_ledger_entry_sign_ck;
--rollback ALTER TABLE real_ledger_entry ADD CONSTRAINT real_ledger_entry_sign_ck CHECK ((entry_kind = 'DEPOSIT' AND lamports > 0) OR (entry_kind IN ('WITHDRAWAL', 'NETWORK_FEE', 'RENT') AND lamports < 0) OR (entry_kind IN ('RESERVE', 'RESERVE_RELEASE') AND lamports > 0) OR (entry_kind IN ('COMPENSATION', 'SWAP_FILL_IN', 'SWAP_FILL_OUT') AND lamports <> 0));

--changeset memetrade:0021-audit-redaction splitStatements:false rollbackSplitStatements:false
-- U5-NFR1.3, security-design.md "Redação da trilha": the trigger of 0005 is replaced by a version that still refuses
-- every UPDATE and TRUNCATE, with ONE exception, true only when the three conditions hold together: the local variable
-- memetrade.audit_redaction is 'on' (only the redaction function sets it, for its own transaction), the new row equals
-- the old one except bot_wallet_address set to NULL, and the old row had it. The application login still has no UPDATE
-- on audit_event, so only the function (running as the owner) can reach the exception. DELETE keeps the rule of 0005.
CREATE OR REPLACE FUNCTION audit_event_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF current_setting('memetrade.audit_redaction', true) = 'on'
           AND OLD.bot_wallet_address IS NOT NULL
           AND NEW.bot_wallet_address IS NULL
           AND (to_jsonb(NEW) - 'bot_wallet_address') = (to_jsonb(OLD) - 'bot_wallet_address') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT pg_has_role(current_user, 'audit_retention', 'MEMBER') THEN
        RAISE EXCEPTION 'audit_event is append-only: DELETE is allowed only to the retention role'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.occurred_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '12 months') AT TIME ZONE 'UTC' THEN
        RAISE EXCEPTION 'audit_event is append-only: the event is inside the 12-month retention window'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

-- The ONLY path that puts NULL in audit_event.bot_wallet_address and removes real.botWalletAddress from the outbox
-- payload, for the bot wallet being closed by an anonymization (BR6.2). SECURITY DEFINER (the schema owner), fixed
-- search_path, EXECUTE only for the application login. It first locks the outbox rows of that address, delivered ones
-- included (the relay locks its batch with FOR UPDATE SKIP LOCKED, so a delivery in progress is waited for and a later
-- one reads the redacted payload), changes nothing else, deletes nothing, and records one REAL_AUDIT_REDACTED event
-- with the counts. Returns the number of rows redacted.
CREATE OR REPLACE FUNCTION redact_real_wallet_audit(p_address text) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    outbox_rows integer;
    event_rows integer;
BEGIN
    IF p_address IS NULL OR p_address !~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$' THEN
        RAISE EXCEPTION 'redact_real_wallet_audit needs a bot wallet address' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM 1 FROM public.audit_outbox WHERE payload -> 'real' ->> 'botWalletAddress' = p_address FOR UPDATE;
    UPDATE public.audit_outbox
        SET payload = jsonb_set(payload, '{real}', (payload -> 'real') - 'botWalletAddress')
        WHERE payload -> 'real' ->> 'botWalletAddress' = p_address;
    GET DIAGNOSTICS outbox_rows = ROW_COUNT;
    PERFORM set_config('memetrade.audit_redaction', 'on', true);
    UPDATE public.audit_event SET bot_wallet_address = NULL WHERE bot_wallet_address = p_address;
    GET DIAGNOSTICS event_rows = ROW_COUNT;
    PERFORM set_config('memetrade.audit_redaction', 'off', true);
    INSERT INTO public.audit_event (event_id, tenant_id, actor, actor_type, action, target_ref, correlation_id,
        occurred_at, recorded_at, details)
    VALUES (gen_random_uuid(), NULL, 'system', 'SYSTEM', 'REAL_AUDIT_REDACTED', NULL, 'redaction', now(), now(),
        jsonb_build_object('eventRows', event_rows::text, 'outboxRows', outbox_rows::text));
    RETURN event_rows + outbox_rows;
END;
$$;
REVOKE ALL ON FUNCTION redact_real_wallet_audit(text) FROM PUBLIC;
DO $$
DECLARE
    grantee_name text;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'audit_outbox'::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner
    LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION redact_real_wallet_audit(text) TO %I', grantee_name);
    END LOOP;
END;
$$;
-- The two selections of the redaction (security-design.md "Chave de seleção").
CREATE INDEX audit_event_bot_wallet_idx ON audit_event (bot_wallet_address) WHERE bot_wallet_address IS NOT NULL;
CREATE INDEX audit_outbox_bot_wallet_idx ON audit_outbox ((payload -> 'real' ->> 'botWalletAddress'))
    WHERE payload -> 'real' ->> 'botWalletAddress' IS NOT NULL;
--rollback DROP INDEX audit_outbox_bot_wallet_idx;
--rollback DROP INDEX audit_event_bot_wallet_idx;
--rollback DROP FUNCTION redact_real_wallet_audit(text);
--rollback CREATE OR REPLACE FUNCTION audit_event_reject_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP USING ERRCODE = 'insufficient_privilege'; END IF; IF NOT pg_has_role(current_user, 'audit_retention', 'MEMBER') THEN RAISE EXCEPTION 'audit_event is append-only: DELETE is allowed only to the retention role' USING ERRCODE = 'insufficient_privilege'; END IF; IF OLD.occurred_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '12 months') AT TIME ZONE 'UTC' THEN RAISE EXCEPTION 'audit_event is append-only: the event is inside the 12-month retention window' USING ERRCODE = 'insufficient_privilege'; END IF; RETURN OLD; END; $$;
