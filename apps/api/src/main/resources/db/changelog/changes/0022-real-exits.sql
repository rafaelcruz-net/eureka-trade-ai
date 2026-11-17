--liquibase formatted sql

-- 261113-trading-real-bot U7 u7-exits-and-fiscal (infrastructure-specification.md "Banco: migração 0022, só de
-- expansão"): the conditional exits (stop, take-profit, OCO) of the REAL positions and the deferral of a REAL decision
-- while the conditional of the position is being cancelled (logical-components.md "Ajustes da revisão"). Expand only:
-- one new table and two new columns with defaults, so the images of the U6 keep working. Every changeset declares its
-- rollback; the new table is dropped only when it is EMPTY (a conditional OPEN or CANCELLING holds the token in the
-- custody of the Jupiter, and the old images cannot read, cancel nor reconcile it: docs/runbooks/real-exits.md). The
-- new columns of real_decision_request stay on a rollback.

--changeset memetrade:0022-real-decision-request-deferral splitStatements:false rollbackSplitStatements:false
-- logical-components.md "Desfecho novo na U4, DEFERRED": a PENDING decision whose sale waits for the cancellation of
-- the conditional of the position gets not_before = now + 30 s and attempts + 1; after 10 attempts it is DISCARDED with
-- EXIT_CANCEL_TIMEOUT; a sale of a position already zero is DISCARDED with NOTHING_TO_SELL (BR4.3).
-- The columns stay on a rollback of this changeset, so applying it again finds them (IF NOT EXISTS).
ALTER TABLE real_decision_request ADD COLUMN IF NOT EXISTS not_before timestamptz NOT NULL DEFAULT now();
ALTER TABLE real_decision_request ADD COLUMN IF NOT EXISTS attempts int NOT NULL DEFAULT 0;
ALTER TABLE real_decision_request DROP CONSTRAINT IF EXISTS real_decision_request_attempts_ck;
ALTER TABLE real_decision_request ADD CONSTRAINT real_decision_request_attempts_ck CHECK (attempts >= 0);
ALTER TABLE real_decision_request DROP CONSTRAINT real_decision_request_discard_value_ck;
ALTER TABLE real_decision_request ADD CONSTRAINT real_decision_request_discard_value_ck CHECK (
    discard_reason IS NULL OR discard_reason IN ('RESERVATION_EXPIRED', 'BOT_NOT_OWNED', 'ORDER_IN_PROGRESS',
    'STATE_CHANGED', 'LIQUIDATION_ACTIVE', 'REAL_DISABLED', 'EXIT_CANCEL_TIMEOUT', 'NOTHING_TO_SELL'));
-- BR2.1 of the U4 kept: a decision is closed once and keeps its identity; while PENDING only its deferral moves.
CREATE OR REPLACE FUNCTION real_decision_request_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'PENDING' AND NEW.state = 'PENDING'
       AND (to_jsonb(NEW) - 'not_before' - 'attempts') = (to_jsonb(OLD) - 'not_before' - 'attempts') THEN
        RETURN NEW;
    END IF;
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('PROCESSED', 'DISCARDED')
       OR (to_jsonb(NEW) - 'state' - 'discard_reason' - 'processed_at')
          <> (to_jsonb(OLD) - 'state' - 'discard_reason' - 'processed_at') THEN
        RAISE EXCEPTION 'real_decision_request: only PENDING -> PROCESSED or DISCARDED is allowed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
DO $$
DECLARE
    grantee_name text;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'real_decision_request'::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner
          AND r.rolname <> 'risk_retention'
    LOOP
        EXECUTE format('GRANT UPDATE (not_before, attempts) ON real_decision_request TO %I', grantee_name);
    END LOOP;
END;
$$;
--rollback CREATE OR REPLACE FUNCTION real_decision_request_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('PROCESSED', 'DISCARDED') OR (to_jsonb(NEW) - 'state' - 'discard_reason' - 'processed_at') <> (to_jsonb(OLD) - 'state' - 'discard_reason' - 'processed_at') THEN RAISE EXCEPTION 'real_decision_request: only PENDING -> PROCESSED or DISCARDED is allowed' USING ERRCODE = 'insufficient_privilege'; END IF; RETURN NEW; END; $$;

--changeset memetrade:0022-real-conditional-order splitStatements:false rollbackSplitStatements:false
-- entities.md ConditionalOrder. The bot wallet is kept by its id (the address is read from M1 when needed), so the
-- anonymization leaves no address here (team.md). Prices in USD are exact decimals; the amount is the whole position in
-- base units (BR3.2, BR3.7, U7-NFR7.1). adapter says who holds the order: SIMULATED (LOCALNET and the test profile,
-- never a transaction) or JUPITER (the Trigger API v2, after the recording of BR0).
CREATE TABLE real_conditional_order (
    conditional_order_id  uuid          PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (tenant_id),
    user_id               uuid          NOT NULL REFERENCES app_user (user_id),
    bot_wallet_id         uuid          NOT NULL,
    position_mint         varchar(44)   NOT NULL,
    kind                  varchar(12)   NOT NULL,
    stop_price_usd        numeric(38,18) NULL,
    take_profit_price_usd numeric(38,18) NULL,
    amount_base_units     numeric(39,0) NOT NULL,
    slippage_bps          int           NOT NULL,
    state                 varchar(20)   NOT NULL,
    adapter               varchar(12)   NOT NULL,
    jupiter_order_id      varchar(120)  NULL,
    create_signature      varchar(90)   NULL,
    fill_signature        varchar(90)   NULL,
    cancel_signature      varchar(90)   NULL,
    escrow_account        varchar(44)   NULL,
    filled_side           varchar(12)   NULL,
    idempotency_key       varchar(200)  NOT NULL,
    signing_message       text          NULL,
    signing_deadline      timestamptz   NULL,
    cancel_reason         varchar(20)   NULL,
    cancel_attempts       int           NOT NULL DEFAULT 0,
    last_read_at          timestamptz   NULL,
    cancelling_since      timestamptz   NULL,
    filled_at             timestamptz   NULL,
    final_at              timestamptz   NULL,
    created_at            timestamptz   NOT NULL,
    updated_at            timestamptz   NOT NULL,
    CONSTRAINT uq_real_conditional_order_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT real_conditional_order_mint_ck CHECK (position_mint ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    CONSTRAINT real_conditional_order_kind_ck CHECK (kind IN ('STOP', 'TAKE_PROFIT', 'OCO')),
    CONSTRAINT real_conditional_order_prices_ck CHECK (
        (kind = 'STOP' AND coalesce(stop_price_usd, 0) > 0 AND take_profit_price_usd IS NULL)
        OR (kind = 'TAKE_PROFIT' AND coalesce(take_profit_price_usd, 0) > 0 AND stop_price_usd IS NULL)
        OR (kind = 'OCO' AND coalesce(stop_price_usd, 0) > 0
            AND coalesce(take_profit_price_usd, 0) > coalesce(stop_price_usd, 0))),
    CONSTRAINT real_conditional_order_amount_ck CHECK (amount_base_units >= 1),
    CONSTRAINT real_conditional_order_slippage_ck CHECK (slippage_bps BETWEEN 1 AND 10000),
    CONSTRAINT real_conditional_order_state_ck CHECK (state IN ('PENDING_SIGNATURE', 'OPEN', 'CANCELLING', 'FILLED',
        'CANCELLED', 'EXPIRED', 'FAILED')),
    CONSTRAINT real_conditional_order_adapter_ck CHECK (adapter IN ('SIMULATED', 'JUPITER')),
    CONSTRAINT real_conditional_order_side_ck CHECK (
        (state = 'FILLED') = (filled_side IS NOT NULL) AND (filled_side IS NULL OR filled_side IN ('STOP',
        'TAKE_PROFIT'))),
    CONSTRAINT real_conditional_order_reason_ck CHECK (
        (state <> 'CANCELLED' OR cancel_reason IS NOT NULL) AND (cancel_reason IS NULL OR cancel_reason IN (
        'KILL_SWITCH', 'MODE_CHANGE', 'RELEASE_REVOKED', 'MARKET_SELL', 'USER'))),
    CONSTRAINT real_conditional_order_final_ck CHECK (
        (state IN ('FILLED', 'CANCELLED', 'EXPIRED', 'FAILED')) = (final_at IS NOT NULL)),
    CONSTRAINT real_conditional_order_attempts_ck CHECK (cancel_attempts >= 0)
);
-- BR3.1, U7-NFR3.5: at most one conditional that is not final per position of the bot wallet.
CREATE UNIQUE INDEX uq_real_conditional_order_open ON real_conditional_order (bot_wallet_id, position_mint)
    WHERE state IN ('PENDING_SIGNATURE', 'OPEN', 'CANCELLING');
-- U7-NFR3.6: the jobs read by wallet and state; the list of the person by creation.
CREATE INDEX real_conditional_order_wallet_state_idx ON real_conditional_order (bot_wallet_id, state);
CREATE INDEX real_conditional_order_user_created_idx ON real_conditional_order (user_id, created_at DESC);
CREATE INDEX real_conditional_order_state_idx ON real_conditional_order (state, updated_at)
    WHERE state IN ('PENDING_SIGNATURE', 'OPEN', 'CANCELLING');
-- U7-NFR3.7: the purge of the final conditionals older than 5 years.
CREATE INDEX real_conditional_order_purge_idx ON real_conditional_order (final_at) WHERE final_at IS NOT NULL;
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM real_conditional_order) THEN RAISE EXCEPTION 'real_conditional_order is not empty: cancel every conditional, keep the migration 0022 and roll back only the images (docs/runbooks/real-exits.md)'; END IF; END $$; DROP TABLE real_conditional_order;

--changeset memetrade:0022-real-conditional-order-triggers splitStatements:false rollbackSplitStatements:false
-- entities.md "Máquina de estados": the identity never changes, a final conditional never changes, and the state moves
-- only along the table (compare-and-set in the application, U7-NFR4.1). CANCELLING never goes back to OPEN (BR6.4).
CREATE OR REPLACE FUNCTION real_conditional_order_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    mutable text[] := ARRAY['state', 'jupiter_order_id', 'create_signature', 'fill_signature', 'cancel_signature',
        'escrow_account', 'filled_side', 'signing_message', 'signing_deadline', 'cancel_reason', 'cancel_attempts',
        'last_read_at', 'cancelling_since', 'filled_at', 'final_at', 'updated_at'];
BEGIN
    IF (to_jsonb(NEW) - mutable) <> (to_jsonb(OLD) - mutable) THEN
        RAISE EXCEPTION 'real_conditional_order: the identity of a conditional never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.state IN ('FILLED', 'CANCELLED', 'EXPIRED', 'FAILED') THEN
        RAISE EXCEPTION 'real_conditional_order: a final conditional never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
        (OLD.state = 'PENDING_SIGNATURE' AND NEW.state IN ('OPEN', 'EXPIRED', 'FAILED', 'CANCELLED'))
        OR (OLD.state = 'OPEN' AND NEW.state IN ('FILLED', 'CANCELLING', 'EXPIRED'))
        OR (OLD.state = 'CANCELLING' AND NEW.state IN ('CANCELLED', 'FILLED'))) THEN
        RAISE EXCEPTION 'real_conditional_order: % to % is not a transition', OLD.state, NEW.state
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER real_conditional_order_guard BEFORE UPDATE ON real_conditional_order
    FOR EACH ROW EXECUTE FUNCTION real_conditional_order_guard();
-- U7-NFR3.7: deleted only by the retention login, 5 years after the conditional ended (the function of the U5).
CREATE TRIGGER real_conditional_order_age BEFORE DELETE ON real_conditional_order
    FOR EACH ROW EXECUTE FUNCTION real_ops_age_guard('final_at', '5 years');
CREATE TRIGGER real_conditional_order_no_truncate BEFORE TRUNCATE ON real_conditional_order
    FOR EACH STATEMENT EXECUTE FUNCTION real_ops_reject_change();
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM real_conditional_order) THEN RAISE EXCEPTION 'real_conditional_order is not empty: cancel every conditional, keep the migration 0022 and roll back only the images (docs/runbooks/real-exits.md)'; END IF; END $$; DROP TRIGGER real_conditional_order_no_truncate ON real_conditional_order; DROP TRIGGER real_conditional_order_age ON real_conditional_order; DROP TRIGGER real_conditional_order_guard ON real_conditional_order; DROP FUNCTION real_conditional_order_guard();

--changeset memetrade:0022-real-conditional-order-privileges splitStatements:false rollbackSplitStatements:false
-- The matrix of privileges (as 0019 and 0021): the application SELECT, INSERT and UPDATE, never DELETE nor TRUNCATE;
-- the retention login SELECT and DELETE (with the age trigger); the owner keeps no TRUNCATE.
REVOKE ALL ON real_conditional_order FROM PUBLIC;
DO $$
DECLARE
    grantee_name text;
    writers text[];
BEGIN
    SELECT array_agg(DISTINCT r.rolname) INTO writers
    FROM pg_class c
    CROSS JOIN LATERAL aclexplode(c.relacl) a
    JOIN pg_roles r ON r.oid = a.grantee
    WHERE c.oid = 'real_conditional_order'::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner;

    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'real_conditional_order'::regclass AND a.grantee <> c.relowner AND r.rolname <> 'risk_retention'
    LOOP
        EXECUTE format('REVOKE ALL ON real_conditional_order FROM %I', grantee_name);
        IF writers IS NOT NULL AND grantee_name = ANY (writers) THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE ON real_conditional_order TO %I', grantee_name);
        END IF;
    END LOOP;
    EXECUTE 'REVOKE TRUNCATE ON real_conditional_order FROM CURRENT_USER';
END;
$$;
GRANT SELECT, DELETE ON real_conditional_order TO risk_retention;
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM real_conditional_order) THEN RAISE EXCEPTION 'real_conditional_order is not empty: cancel every conditional, keep the migration 0022 and roll back only the images (docs/runbooks/real-exits.md)'; END IF; END $$; REVOKE SELECT, DELETE ON real_conditional_order FROM risk_retention;
