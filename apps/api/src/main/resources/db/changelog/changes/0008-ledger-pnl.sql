--liquibase formatted sql

-- U7 Ledger and P&L (entities.md, security-design.md, performance-design.md, scalability-design.md). Expand only:
-- new tables, indexes, checks, triggers, privileges and the seeded policy v1; nothing of U1 to U4 changes, so the U4
-- image keeps working on this schema (rollback of cicd-pipeline.md). Applied by db-migrate with the schema OWNER.
-- Scales: USDC amounts numeric(38,6), unit prices numeric(38,12), token quantities numeric(38,18) (the mint decimals
-- bound the real scale), percentage points numeric(9,4). Never floating point. Every row is PAPER (BR9.2).

--changeset memetrade:0008-simulation-policy
CREATE TABLE simulation_policy (
    version                       varchar(16)  PRIMARY KEY,
    spread_pct                    numeric(9,4) NOT NULL,
    impact_per_consumed_pct       numeric(9,4) NOT NULL,
    fee_pct                       numeric(9,4) NOT NULL,
    max_liquidity_consumption_pct numeric(9,4) NOT NULL,
    money_scale                   smallint     NOT NULL,
    price_scale                   smallint     NOT NULL,
    money_rounding                varchar(16)  NOT NULL,
    quantity_rounding             varchar(8)   NOT NULL,
    activated_at                  timestamptz  NOT NULL,
    CONSTRAINT simulation_policy_rounding_ck CHECK (money_rounding = 'HALF_EVEN' AND quantity_rounding = 'DOWN'),
    CONSTRAINT simulation_policy_values_ck CHECK (
        spread_pct >= 0 AND impact_per_consumed_pct >= 0 AND fee_pct >= 0
        AND max_liquidity_consumption_pct > 0 AND max_liquidity_consumption_pct <= 100)
);
-- BR1.1: policy v1 (spread 0.5000, impact 1.0000 per consumed point, fee 0.3000, cap 10.0000).
INSERT INTO simulation_policy (version, spread_pct, impact_per_consumed_pct, fee_pct, max_liquidity_consumption_pct,
                               money_scale, price_scale, money_rounding, quantity_rounding, activated_at)
VALUES ('v1', 0.5000, 1.0000, 0.3000, 10.0000, 6, 12, 'HALF_EVEN', 'DOWN', '2026-10-01T00:00:00Z');
--rollback DROP TABLE simulation_policy;

--changeset memetrade:0008-paper-account
-- One PAPER account per bot, created by the FUNDING of U5's activation (Q2); how U7 knows the owner of a bot.
CREATE TABLE paper_account (
    bot_id        uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id uuid        NOT NULL REFERENCES app_user (user_id),
    mode          varchar(8)  NOT NULL DEFAULT 'PAPER',
    status        varchar(16) NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT paper_account_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT paper_account_status_ck CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED'))
);
CREATE INDEX paper_account_owner_idx ON paper_account (owner_user_id, tenant_id);
CREATE INDEX paper_account_status_idx ON paper_account (status);
--rollback DROP TABLE paper_account;

--changeset memetrade:0008-paper-order
-- BR3.1: one order per (tenant, mode, key); BR3.4: key and limits version never null, decision only in TRADE.
CREATE TABLE paper_order (
    order_id           uuid           PRIMARY KEY,
    tenant_id          uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id      uuid           NOT NULL REFERENCES app_user (user_id),
    bot_id             uuid           NOT NULL REFERENCES paper_account (bot_id),
    mode               varchar(8)     NOT NULL DEFAULT 'PAPER',
    decision_id        uuid           NULL,
    idempotency_key    varchar(200)   NOT NULL,
    content_digest     char(64)       NOT NULL,
    kind               varchar(8)     NOT NULL,
    side               varchar(4)     NOT NULL,
    chain              varchar(64)    NOT NULL,
    token_mint         varchar(64)    NOT NULL,
    requested_quantity numeric(38,18) NOT NULL,
    limits_version     bigint         NOT NULL,
    status             varchar(16)    NOT NULL,
    not_filled_reason  varchar(32)    NULL,
    created_at         timestamptz    NOT NULL,
    CONSTRAINT uq_paper_order_key UNIQUE (tenant_id, mode, idempotency_key),
    CONSTRAINT paper_order_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT paper_order_kind_ck CHECK (
        (kind = 'TRADE' AND decision_id IS NOT NULL) OR (kind = 'CLOSE' AND decision_id IS NULL)),
    CONSTRAINT paper_order_side_ck CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT paper_order_quantity_ck CHECK (requested_quantity > 0),
    CONSTRAINT paper_order_status_ck CHECK (status IN ('FILLED', 'PARTIALLY_FILLED', 'NOT_FILLED', 'CLOSE_PENDING')),
    CONSTRAINT paper_order_reason_ck CHECK (
        (status = 'NOT_FILLED' AND not_filled_reason IN ('PRICE_UNAVAILABLE', 'NO_LIQUIDITY', 'QUANTITY_BELOW_MINIMUM',
            'NO_POSITION', 'ACCOUNT_CLOSING', 'INSUFFICIENT_BALANCE', 'TENANT_MISMATCH'))
        OR (status <> 'NOT_FILLED' AND not_filled_reason IS NULL)),
    CONSTRAINT paper_order_digest_ck CHECK (content_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX paper_order_bot_created_idx ON paper_order (bot_id, created_at DESC, order_id DESC);
CREATE INDEX paper_order_owner_status_idx ON paper_order (owner_user_id, status);
--rollback DROP TABLE paper_order;

--changeset memetrade:0008-fill
-- One fill per order with every hypothesis of the policy (FR9.1, AC4.11.1).
CREATE TABLE fill (
    fill_id                   uuid           PRIMARY KEY,
    order_id                  uuid           NOT NULL REFERENCES paper_order (order_id),
    tenant_id                 uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id             uuid           NOT NULL REFERENCES app_user (user_id),
    bot_id                    uuid           NOT NULL REFERENCES paper_account (bot_id),
    mode                      varchar(8)     NOT NULL DEFAULT 'PAPER',
    chain                     varchar(64)    NOT NULL,
    token_mint                varchar(64)    NOT NULL,
    filled_quantity           numeric(38,18) NOT NULL,
    reference_price           numeric(38,12) NOT NULL,
    quoted_price              numeric(38,12) NOT NULL,
    fill_price                numeric(38,12) NOT NULL,
    spread_pct                numeric(9,4)   NOT NULL,
    impact_pct                numeric(9,4)   NOT NULL,
    slippage_pct              numeric(9,4)   NOT NULL,
    liquidity_consumed_pct    numeric(9,4)   NOT NULL,
    liquidity_used            numeric(38,6)  NOT NULL,
    gross_amount              numeric(38,6)  NOT NULL,
    fees                      numeric(38,6)  NOT NULL,
    realized                  numeric(38,6)  NULL,
    market_source             varchar(64)    NOT NULL,
    market_observed_at        timestamptz    NOT NULL,
    simulation_policy_version varchar(16)    NOT NULL REFERENCES simulation_policy (version),
    filled_at                 timestamptz    NOT NULL,
    -- Insertion order: two fills of the same instant replay in the order they happened (reconciliation, BR8.2).
    fill_seq                  bigint         GENERATED ALWAYS AS IDENTITY,
    CONSTRAINT uq_fill_order UNIQUE (order_id),
    CONSTRAINT fill_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT fill_values_ck CHECK (
        filled_quantity > 0 AND reference_price > 0 AND fill_price >= 0 AND gross_amount >= 0 AND fees >= 0)
);
CREATE INDEX fill_bot_filled_idx ON fill (bot_id, filled_at);
CREATE INDEX fill_owner_filled_idx ON fill (owner_user_id, filled_at);
--rollback DROP TABLE fill;

--changeset memetrade:0008-ledger-entry
-- Append-only USDC entries with sign (FR10.1); a correction is only a COMPENSATION of an original (BR4.2).
CREATE TABLE ledger_entry (
    entry_id             uuid          PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id        uuid          NOT NULL REFERENCES app_user (user_id),
    bot_id               uuid          NOT NULL REFERENCES paper_account (bot_id),
    mode                 varchar(8)    NOT NULL DEFAULT 'PAPER',
    type                 varchar(16)   NOT NULL,
    asset                varchar(16)   NOT NULL,
    amount               numeric(38,6) NOT NULL,
    reference_id         varchar(200)  NOT NULL,
    compensates_entry_id uuid          NULL REFERENCES ledger_entry (entry_id),
    created_at           timestamptz   NOT NULL,
    CONSTRAINT uq_ledger_entry_compensates UNIQUE (compensates_entry_id),
    CONSTRAINT ledger_entry_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT ledger_entry_type_ck CHECK (
        type IN ('FUNDING', 'TRADE_COST', 'TRADE_PROCEEDS', 'SLIPPAGE', 'FEE', 'CLOSE', 'COMPENSATION')),
    CONSTRAINT ledger_entry_asset_ck CHECK (asset = 'USDC'),
    CONSTRAINT ledger_entry_amount_ck CHECK (amount <> 0),
    CONSTRAINT ledger_entry_compensation_ck CHECK ((type = 'COMPENSATION') = (compensates_entry_id IS NOT NULL))
);
-- BR2.6: at most one FUNDING per bot.
CREATE UNIQUE INDEX uq_ledger_entry_funding ON ledger_entry (bot_id) WHERE type = 'FUNDING';
-- NFR2.4: the balance is a SUM over a covering index (no balance column, no UPDATE outside the matrix).
CREATE INDEX ledger_entry_balance_idx ON ledger_entry (bot_id, mode) INCLUDE (amount);
-- NFR3.2: first page of the ledger by tuple cursor, without OFFSET.
CREATE INDEX ledger_entry_owner_created_idx ON ledger_entry (owner_user_id, created_at DESC, entry_id DESC);
-- NFR3.6: the reconciliation groups the entries of each order.
CREATE INDEX ledger_entry_reference_idx ON ledger_entry (reference_id);
--rollback DROP TABLE ledger_entry;

--changeset memetrade:0008-position
-- Quantity and TOTAL cost per bot and token (FR10.2): zero quantity if and only if zero cost; CLOSED iff zero.
CREATE TABLE position (
    position_id    uuid           PRIMARY KEY,
    tenant_id      uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id  uuid           NOT NULL REFERENCES app_user (user_id),
    bot_id         uuid           NOT NULL REFERENCES paper_account (bot_id),
    mode           varchar(8)     NOT NULL DEFAULT 'PAPER',
    chain          varchar(64)    NOT NULL,
    token_mint     varchar(64)    NOT NULL,
    token_decimals smallint       NOT NULL,
    quantity       numeric(38,18) NOT NULL,
    cost_basis     numeric(38,6)  NOT NULL,
    status         varchar(16)    NOT NULL,
    updated_at     timestamptz    NOT NULL,
    version        bigint         NOT NULL,
    CONSTRAINT uq_position_token UNIQUE (bot_id, chain, token_mint),
    CONSTRAINT position_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT position_decimals_ck CHECK (token_decimals BETWEEN 0 AND 18),
    CONSTRAINT position_non_negative_ck CHECK (quantity >= 0 AND cost_basis >= 0),
    CONSTRAINT position_zero_together_ck CHECK ((quantity = 0) = (cost_basis = 0)),
    CONSTRAINT position_status_ck CHECK (status IN ('OPEN', 'CLOSE_PENDING', 'CLOSED')),
    CONSTRAINT position_closed_iff_zero_ck CHECK ((status = 'CLOSED') = (quantity = 0))
);
CREATE INDEX position_status_idx ON position (status);
--rollback DROP TABLE position;

--changeset memetrade:0008-pnl-daily
-- Q1 of the NFR Design: derived daily summary for the report only, written in the fill's transaction; the barrier
-- never reads it (the day loss comes straight from the fills) and the reconciliation checks it (SUMMARY_MISMATCH).
CREATE TABLE pnl_daily (
    tenant_id     uuid          NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id uuid          NOT NULL REFERENCES app_user (user_id),
    bot_id        uuid          NOT NULL REFERENCES paper_account (bot_id),
    mode          varchar(8)    NOT NULL DEFAULT 'PAPER',
    chain         varchar(64)   NOT NULL,
    token_mint    varchar(64)   NOT NULL,
    utc_day       date          NOT NULL,
    realized      numeric(38,6) NOT NULL,
    fees          numeric(38,6) NOT NULL,
    fill_count    integer       NOT NULL,
    updated_at    timestamptz   NOT NULL,
    CONSTRAINT pnl_daily_pk PRIMARY KEY (bot_id, mode, chain, token_mint, utc_day),
    CONSTRAINT pnl_daily_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT pnl_daily_count_ck CHECK (fill_count >= 0)
);
CREATE INDEX pnl_daily_owner_day_idx ON pnl_daily (owner_user_id, utc_day);
--rollback DROP TABLE pnl_daily;

--changeset memetrade:0008-equity-peak
-- Owned by the pnl module: highest virtual equity of the bot, for the drawdown of U6 (Q10).
CREATE TABLE equity_peak (
    bot_id         uuid          PRIMARY KEY REFERENCES paper_account (bot_id),
    tenant_id      uuid          NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id  uuid          NOT NULL REFERENCES app_user (user_id),
    mode           varchar(8)    NOT NULL DEFAULT 'PAPER',
    peak_equity    numeric(38,6) NOT NULL,
    peak_at        timestamptz   NOT NULL,
    last_equity    numeric(38,6) NULL,
    last_equity_at timestamptz   NOT NULL,
    CONSTRAINT equity_peak_mode_ck CHECK (mode = 'PAPER')
);
--rollback DROP TABLE equity_peak;

--changeset memetrade:0008-reconciliation-check
-- Owned by the pnl module: result of each check per person (Q9); the job keeps the last 100 per person.
CREATE TABLE reconciliation_check (
    check_id         uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id    uuid        NOT NULL REFERENCES app_user (user_id),
    mode             varchar(8)  NOT NULL DEFAULT 'PAPER',
    status           varchar(16) NOT NULL,
    divergence_count integer     NOT NULL,
    divergences      jsonb       NOT NULL,
    check_trigger    varchar(16) NOT NULL,
    checked_at       timestamptz NOT NULL,
    CONSTRAINT reconciliation_check_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT reconciliation_check_status_ck CHECK (status IN ('RECONCILED', 'DIVERGENT')),
    CONSTRAINT reconciliation_check_trigger_ck CHECK (check_trigger IN ('SCHEDULED', 'ON_DEMAND')),
    CONSTRAINT reconciliation_check_count_ck CHECK (
        divergence_count >= 0 AND (status = 'RECONCILED') = (divergence_count = 0))
);
CREATE INDEX reconciliation_check_owner_idx ON reconciliation_check (owner_user_id, checked_at DESC);
--rollback DROP TABLE reconciliation_check;

--changeset memetrade:0008-triggers splitStatements:false
-- BR4.1: ledger_entry and fill refuse UPDATE, DELETE and TRUNCATE for EVERY login (not SECURITY DEFINER). Messages
-- never carry row values.
CREATE OR REPLACE FUNCTION ledger_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER ledger_entry_no_update_delete BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER ledger_entry_no_truncate BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER fill_no_update_delete BEFORE UPDATE OR DELETE ON fill
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER fill_no_truncate BEFORE TRUNCATE ON fill
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

-- BR4.2: a COMPENSATION is the exact negative of an original entry of the same bot that is not itself a compensation;
-- the unique constraint on compensates_entry_id makes it at most once.
CREATE OR REPLACE FUNCTION ledger_check_compensation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    original ledger_entry%ROWTYPE;
BEGIN
    IF NEW.type <> 'COMPENSATION' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO original FROM ledger_entry WHERE entry_id = NEW.compensates_entry_id;
    IF NOT FOUND OR original.type = 'COMPENSATION' OR original.bot_id <> NEW.bot_id
       OR NEW.amount <> -original.amount OR NEW.reference_id <> original.entry_id::text THEN
        RAISE EXCEPTION 'ledger_entry: invalid compensation' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ledger_entry_compensation BEFORE INSERT ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_check_compensation();

-- security-design.md: an order changes only from CLOSE_PENDING to FILLED, every other column equal.
CREATE OR REPLACE FUNCTION paper_order_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'paper_order: DELETE is not allowed' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'CLOSE_PENDING' AND NEW.status = 'FILLED'
       AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'paper_order: transition not allowed' USING ERRCODE = 'check_violation';
END;
$$;
CREATE TRIGGER paper_order_guard BEFORE UPDATE OR DELETE ON paper_order
    FOR EACH ROW EXECUTE FUNCTION paper_order_guard_update();

-- security-design.md: OPEN -> CLOSING and CLOSING -> CLOSED only, the last one without any position of the bot with
-- quantity above zero; every other column equal.
CREATE OR REPLACE FUNCTION paper_account_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'paper_account: DELETE is not allowed' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (to_jsonb(NEW) - 'status') <> (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'paper_account: only the status changes' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'CLOSING' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'CLOSING' AND NEW.status = 'CLOSED'
       AND NOT EXISTS (SELECT 1 FROM position p WHERE p.bot_id = NEW.bot_id AND p.quantity > 0) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'paper_account: transition not allowed' USING ERRCODE = 'check_violation';
END;
$$;
CREATE TRIGGER paper_account_guard BEFORE UPDATE OR DELETE ON paper_account
    FOR EACH ROW EXECUTE FUNCTION paper_account_guard_update();

-- Q10: the peak never goes down.
CREATE OR REPLACE FUNCTION equity_peak_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.peak_equity < OLD.peak_equity THEN
        RAISE EXCEPTION 'equity_peak: the peak never decreases' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER equity_peak_guard BEFORE UPDATE ON equity_peak
    FOR EACH ROW EXECUTE FUNCTION equity_peak_guard_update();

-- Q1 of the NFR Design: fill_count never decreases and grows by one per fill; without a new fill only the fees change
-- (the compensation of a FEE).
CREATE OR REPLACE FUNCTION pnl_daily_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.fill_count < OLD.fill_count OR NEW.fill_count > OLD.fill_count + 1 THEN
        RAISE EXCEPTION 'pnl_daily: fill_count only grows by one' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.fill_count = OLD.fill_count AND NEW.realized <> OLD.realized THEN
        RAISE EXCEPTION 'pnl_daily: without a new fill only the fees change' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pnl_daily_guard BEFORE UPDATE ON pnl_daily
    FOR EACH ROW EXECUTE FUNCTION pnl_daily_guard_update();
--rollback DROP TRIGGER pnl_daily_guard ON pnl_daily; DROP FUNCTION pnl_daily_guard_update(); DROP TRIGGER equity_peak_guard ON equity_peak; DROP FUNCTION equity_peak_guard_update(); DROP TRIGGER paper_account_guard ON paper_account; DROP FUNCTION paper_account_guard_update(); DROP TRIGGER paper_order_guard ON paper_order; DROP FUNCTION paper_order_guard_update(); DROP TRIGGER ledger_entry_compensation ON ledger_entry; DROP FUNCTION ledger_check_compensation(); DROP TRIGGER fill_no_truncate ON fill; DROP TRIGGER fill_no_update_delete ON fill; DROP TRIGGER ledger_entry_no_truncate ON ledger_entry; DROP TRIGGER ledger_entry_no_update_delete ON ledger_entry; DROP FUNCTION ledger_reject_change();

--changeset memetrade:0008-privileges splitStatements:false
-- NFR6.2, privilege matrix of security-design.md. The default privileges of db-bootstrap give the application login
-- SELECT, INSERT, UPDATE and DELETE on every new table; whatever its name (it comes from .env), every non-owner grantee
-- that can INSERT keeps only what the matrix allows, and every other non-owner grantee keeps at most SELECT.
REVOKE ALL ON simulation_policy, paper_account, paper_order, fill, ledger_entry, position, pnl_daily, equity_peak,
    reconciliation_check FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('simulation_policy', NULL, false, false),
            ('paper_account', 'status', true, false),
            ('paper_order', 'status', true, false),
            ('fill', NULL, true, false),
            ('ledger_entry', NULL, true, false),
            ('position', 'quantity, cost_basis, status, updated_at, version', true, false),
            ('pnl_daily', 'realized, fees, fill_count, updated_at', true, false),
            ('equity_peak', 'peak_equity, peak_at, last_equity, last_equity_at', true, false),
            ('reconciliation_check', NULL, true, true)
        ) AS s(table_name, update_columns, can_insert, can_delete)
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
            WHERE c.oid = spec.table_name::regclass AND a.grantee <> c.relowner
        LOOP
            EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON %I FROM %I',
                           spec.table_name, grantee_name);
            IF writers IS NOT NULL AND grantee_name = ANY (writers) THEN
                IF spec.can_insert THEN
                    EXECUTE format('GRANT INSERT ON %I TO %I', spec.table_name, grantee_name);
                END IF;
                IF spec.update_columns IS NOT NULL THEN
                    EXECUTE format('GRANT UPDATE (%s) ON %I TO %I', spec.update_columns, spec.table_name, grantee_name);
                END IF;
                IF spec.can_delete THEN
                    EXECUTE format('GRANT DELETE ON %I TO %I', spec.table_name, grantee_name);
                END IF;
            END IF;
        END LOOP;
    END LOOP;
END;
$$;
--rollback SELECT 1;
