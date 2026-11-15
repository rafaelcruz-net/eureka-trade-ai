--liquibase formatted sql

-- 261113-trading-real-bot, U1 u1-bot-wallet (entities.md, performance-design.md). Expand only: no existing column is
-- removed or changed. The REAL ledger is a table of its own (real_ledger_entry): ledger_entry stays PAPER-only, so no
-- PAPER query can ever read a REAL row (BR6.3, NFR2.1). Lamports are bigint, never floating point (NFR10.1); instants
-- are timestamptz (UTC). The key reference of the bot wallet is NOT stored here: it stays in the TxSigner (NFR1.7).

--changeset memetrade:0016-real-ledger-entry splitStatements:false
CREATE TABLE real_ledger_entry (
    entry_id         uuid         PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id    uuid         NOT NULL,
    mode             varchar(8)   NOT NULL DEFAULT 'REAL',
    entry_kind       varchar(16)  NOT NULL,
    lamports         bigint       NOT NULL,
    mint             varchar(44)  NULL,
    token_base_units numeric(30,0) NULL,
    tx_signature     varchar(90)  NULL,
    reference        varchar(200) NOT NULL,
    idempotency_key  varchar(200) NOT NULL,
    created_at       timestamptz  NOT NULL,
    CONSTRAINT uq_real_ledger_entry_idempotency UNIQUE (idempotency_key),
    CONSTRAINT real_ledger_entry_mode_ck CHECK (mode = 'REAL'),
    CONSTRAINT real_ledger_entry_kind_ck CHECK (entry_kind IN ('DEPOSIT', 'WITHDRAWAL', 'NETWORK_FEE', 'RESERVE',
        'RESERVE_RELEASE', 'COMPENSATION', 'SWAP_FILL_IN', 'SWAP_FILL_OUT', 'RENT')),
    -- BR3.5: balance entries carry their sign; reserve entries are always positive.
    CONSTRAINT real_ledger_entry_sign_ck CHECK (
        (entry_kind = 'DEPOSIT' AND lamports > 0)
        OR (entry_kind IN ('WITHDRAWAL', 'NETWORK_FEE', 'RENT') AND lamports < 0)
        OR (entry_kind IN ('RESERVE', 'RESERVE_RELEASE') AND lamports > 0)
        OR (entry_kind IN ('COMPENSATION', 'SWAP_FILL_IN', 'SWAP_FILL_OUT') AND lamports <> 0))
);
-- NFR3.1: the balance is a SUM over a covering index.
CREATE INDEX real_ledger_entry_balance_idx ON real_ledger_entry (bot_wallet_id, entry_kind) INCLUDE (lamports);
CREATE INDEX real_ledger_entry_reference_idx ON real_ledger_entry (bot_wallet_id, reference);
CREATE INDEX real_ledger_entry_tenant_created_idx ON real_ledger_entry (tenant_id, created_at DESC, entry_id DESC);
-- BR6.3: append-only for every login, like ledger_entry (same function).
CREATE TRIGGER real_ledger_entry_no_update_delete BEFORE UPDATE OR DELETE ON real_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER real_ledger_entry_no_truncate BEFORE TRUNCATE ON real_ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
--rollback DROP TABLE real_ledger_entry;

--changeset memetrade:0016-bot-wallet-account
CREATE TABLE bot_wallet_account (
    bot_wallet_id         uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL REFERENCES tenant (tenant_id),
    user_id               uuid        NOT NULL REFERENCES app_user (user_id),
    public_address        varchar(44) NOT NULL,
    main_wallet_address   varchar(44) NOT NULL,
    network               varchar(16) NOT NULL,
    status                varchar(16) NOT NULL DEFAULT 'ACTIVE',
    deposit_cursor        varchar(90) NULL,
    policy_reset_required boolean     NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL,
    closed_at             timestamptz NULL,
    CONSTRAINT uq_bot_wallet_account_address UNIQUE (public_address),
    CONSTRAINT bot_wallet_account_address_ck CHECK (public_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    CONSTRAINT bot_wallet_account_main_ck CHECK (main_wallet_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    CONSTRAINT bot_wallet_account_network_ck CHECK (network IN ('LOCALNET', 'DEVNET', 'MAINNET')),
    CONSTRAINT bot_wallet_account_status_ck CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT bot_wallet_account_closed_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
-- BR1.4: one ACTIVE wallet per person.
CREATE UNIQUE INDEX uq_bot_wallet_account_active_user ON bot_wallet_account (user_id) WHERE status = 'ACTIVE';
CREATE INDEX bot_wallet_account_tenant_idx ON bot_wallet_account (tenant_id, user_id);
--rollback DROP TABLE bot_wallet_account;

--changeset memetrade:0016-real-ledger-entry-fk
ALTER TABLE real_ledger_entry ADD CONSTRAINT real_ledger_entry_wallet_fk
    FOREIGN KEY (bot_wallet_id) REFERENCES bot_wallet_account (bot_wallet_id);
--rollback ALTER TABLE real_ledger_entry DROP CONSTRAINT real_ledger_entry_wallet_fk;

--changeset memetrade:0016-real-preferences
CREATE TABLE real_preferences (
    bot_wallet_id               uuid        PRIMARY KEY REFERENCES bot_wallet_account (bot_wallet_id),
    operation_mode              varchar(16) NOT NULL DEFAULT 'SIGNED',
    cap_lamports                bigint      NOT NULL,
    max_cap_lamports            bigint      NOT NULL DEFAULT 10000000000,
    max_slippage_bps            integer     NOT NULL DEFAULT 200,
    fee_reserve_lamports        bigint      NOT NULL,
    max_withdrawal_fee_lamports bigint      NOT NULL,
    paused_reasons              text[]      NOT NULL DEFAULT '{}',
    updated_at                  timestamptz NOT NULL,
    CONSTRAINT real_preferences_mode_ck CHECK (operation_mode IN ('SIGNED', 'AUTO_EXITS', 'AUTO_FULL')),
    CONSTRAINT real_preferences_cap_ck CHECK (cap_lamports >= 0 AND cap_lamports <= max_cap_lamports),
    CONSTRAINT real_preferences_max_cap_ck CHECK (max_cap_lamports BETWEEN 0 AND 10000000000),
    CONSTRAINT real_preferences_slippage_ck CHECK (max_slippage_bps BETWEEN 0 AND 500),
    CONSTRAINT real_preferences_reserve_ck CHECK (fee_reserve_lamports >= 0 AND max_withdrawal_fee_lamports >= 0),
    CONSTRAINT real_preferences_paused_ck CHECK (paused_reasons <@ ARRAY['USER', 'DIVERGENCE', 'DAILY_LOSS',
        'KILL_SWITCH', 'ADMIN', 'UPSTREAM']::text[])
);
--rollback DROP TABLE real_preferences;

--changeset memetrade:0016-consent-challenge
CREATE TABLE consent_challenge (
    challenge_id uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (tenant_id),
    user_id      uuid        NOT NULL REFERENCES app_user (user_id),
    kind         varchar(16) NOT NULL,
    message      text        NOT NULL,
    nonce        varchar(64) NOT NULL,
    mode         varchar(16) NOT NULL,
    cap_lamports bigint      NOT NULL,
    issued_at    timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    state        varchar(16) NOT NULL DEFAULT 'PENDING',
    in_use_since timestamptz NULL,
    consumed_at  timestamptz NULL,
    CONSTRAINT uq_consent_challenge_nonce UNIQUE (nonce),
    CONSTRAINT consent_challenge_kind_ck CHECK (kind IN ('ACTIVATE', 'RAISE_MODE', 'RAISE_CAP')),
    CONSTRAINT consent_challenge_state_ck CHECK (state IN ('PENDING', 'IN_USE', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT consent_challenge_window_ck CHECK (expires_at = issued_at + interval '5 minutes'),
    CONSTRAINT consent_challenge_consumed_ck CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL))
);
CREATE INDEX consent_challenge_user_idx ON consent_challenge (tenant_id, user_id, issued_at DESC);
-- NFR4.7: the stuck IN_USE challenges go back to PENDING after 30 s.
CREATE INDEX consent_challenge_in_use_idx ON consent_challenge (in_use_since) WHERE state = 'IN_USE';
--rollback DROP TABLE consent_challenge;

--changeset memetrade:0016-wallet-consent
CREATE TABLE wallet_consent (
    consent_id              uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id           uuid        NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    kind                    varchar(16) NOT NULL,
    nonce                   varchar(64) NOT NULL,
    expires_at              timestamptz NOT NULL,
    authorized_mode         varchar(16) NOT NULL,
    authorized_cap_lamports bigint      NOT NULL,
    signed_message_digest   char(64)    NOT NULL,
    signed_at               timestamptz NOT NULL,
    CONSTRAINT uq_wallet_consent_nonce UNIQUE (nonce),
    CONSTRAINT wallet_consent_kind_ck CHECK (kind IN ('ACTIVATE', 'RAISE_MODE', 'RAISE_CAP'))
);
--rollback DROP TABLE wallet_consent;

--changeset memetrade:0016-used-nonce
-- BR1.8: every accepted nonce until its consent expired plus one day.
CREATE TABLE used_nonce (
    nonce       varchar(64) PRIMARY KEY,
    purge_after timestamptz NOT NULL
);
CREATE INDEX used_nonce_purge_idx ON used_nonce (purge_after);
--rollback DROP TABLE used_nonce;

--changeset memetrade:0016-real-release
-- BR7.1: in the U1 a release is written only by the seed of the test and local profiles; it takes effect 24 h later.
CREATE TABLE real_release (
    release_id   uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES app_user (user_id),
    network      varchar(16) NOT NULL,
    requested_by uuid        NOT NULL,
    requested_at timestamptz NOT NULL,
    effective_at timestamptz NOT NULL,
    revoked_at   timestamptz NULL,
    CONSTRAINT real_release_network_ck CHECK (network IN ('LOCALNET', 'DEVNET', 'MAINNET')),
    CONSTRAINT real_release_quarantine_ck CHECK (effective_at = requested_at + interval '24 hours')
);
CREATE INDEX real_release_user_idx ON real_release (user_id, network);
--rollback DROP TABLE real_release;

--changeset memetrade:0016-observed-deposit
CREATE TABLE observed_deposit (
    tx_signature        varchar(90) PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id       uuid        NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    source_address_hmac char(64)    NOT NULL,
    lamports            bigint      NOT NULL,
    finalized_at        timestamptz NOT NULL,
    known_source        boolean     NOT NULL,
    CONSTRAINT observed_deposit_lamports_ck CHECK (lamports > 0)
);
CREATE INDEX observed_deposit_wallet_idx ON observed_deposit (bot_wallet_id, finalized_at DESC, tx_signature DESC);
CREATE TRIGGER observed_deposit_no_update_delete BEFORE UPDATE OR DELETE ON observed_deposit
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
--rollback DROP TABLE observed_deposit;

--changeset memetrade:0016-withdrawal
CREATE TABLE withdrawal (
    withdrawal_id      uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id      uuid        NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    kind               varchar(8)  NOT NULL,
    lamports           bigint      NOT NULL,
    fee_lamports       bigint      NOT NULL,
    state              varchar(16) NOT NULL DEFAULT 'REQUESTED',
    idempotency_key    uuid        NOT NULL,
    signing_request_id uuid        NOT NULL,
    blockhash          varchar(44) NULL,
    last_valid_height  bigint      NULL,
    signed_transaction text        NULL,
    message_digest     char(64)    NULL,
    tx_signature       varchar(90) NULL,
    failure_code       varchar(40) NULL,
    attempts           integer     NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz NOT NULL,
    requested_at       timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT uq_withdrawal_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_withdrawal_signing_request UNIQUE (signing_request_id),
    CONSTRAINT withdrawal_kind_ck CHECK (kind IN ('PARTIAL', 'TOTAL')),
    CONSTRAINT withdrawal_state_ck CHECK (state IN ('REQUESTED', 'SIGNED', 'SENT', 'CONFIRMED', 'FAILED', 'AMBIGUOUS')),
    CONSTRAINT withdrawal_amount_ck CHECK (lamports > 0 AND fee_lamports >= 0),
    -- BR4.7: from SIGNED on, the signed transaction and its digest are kept for every resend.
    CONSTRAINT withdrawal_signed_ck CHECK (
        state IN ('REQUESTED', 'FAILED') OR (signed_transaction IS NOT NULL AND message_digest IS NOT NULL))
);
-- reliability-design.md: the follow-up picks the ready withdrawals with SKIP LOCKED.
CREATE INDEX withdrawal_ready_idx ON withdrawal (state, next_attempt_at)
    WHERE state IN ('REQUESTED', 'SIGNED', 'SENT', 'AMBIGUOUS');
CREATE INDEX withdrawal_wallet_idx ON withdrawal (bot_wallet_id, requested_at DESC, withdrawal_id DESC);
--rollback DROP TABLE withdrawal;

--changeset memetrade:0016-reconciliation-mark
CREATE TABLE reconciliation_mark (
    mark_id       uuid        PRIMARY KEY,
    bot_wallet_id uuid        NOT NULL REFERENCES bot_wallet_account (bot_wallet_id),
    reason        varchar(32) NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT reconciliation_mark_reason_ck CHECK (reason IN ('WITHDRAWAL_CONFIRMED', 'DEPOSIT_UNKNOWN_SOURCE',
        'DATABASE_RESTORED'))
);
CREATE INDEX reconciliation_mark_wallet_idx ON reconciliation_mark (bot_wallet_id, created_at);
--rollback DROP TABLE reconciliation_mark;

--changeset memetrade:0016-audit-typed-references
-- BR6.2 (M8): the transaction signature, the mint and the bot wallet have their own columns; they are still refused in
-- the free details. The main wallet is never stored in clear (only wallet_hmac).
ALTER TABLE audit_event ADD COLUMN tx_signature varchar(90) NULL;
ALTER TABLE audit_event ADD COLUMN mint varchar(44) NULL;
ALTER TABLE audit_event ADD COLUMN bot_wallet_address varchar(44) NULL;
--rollback ALTER TABLE audit_event DROP COLUMN bot_wallet_address; ALTER TABLE audit_event DROP COLUMN mint; ALTER TABLE audit_event DROP COLUMN tx_signature;

--changeset memetrade:0016-real-grants splitStatements:false
-- Least privilege: the application login (every non-owner grantee that can INSERT) loses UPDATE and DELETE on the
-- append-only tables; the triggers refuse them for every login anyway.
DO $$
DECLARE
    grantee_name text;
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['real_ledger_entry', 'observed_deposit', 'wallet_consent'] LOOP
        FOR grantee_name IN
            SELECT r.rolname
            FROM pg_class c
            CROSS JOIN LATERAL aclexplode(c.relacl) a
            JOIN pg_roles r ON r.oid = a.grantee
            WHERE c.oid = table_name::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner
        LOOP
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM %I', table_name, grantee_name);
        END LOOP;
    END LOOP;
END;
$$;
--rollback SELECT 1;
