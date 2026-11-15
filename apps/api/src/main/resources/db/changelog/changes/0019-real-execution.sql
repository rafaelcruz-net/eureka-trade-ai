--liquibase formatted sql

-- 261113-trading-real-bot, U4 u4-real-execution (entities.md, scalability-design.md, reliability-design.md,
-- infrastructure-specification.md "Banco"). Expand only: one new column with a default on bot (market) and six new
-- tables with their indexes, checks, triggers and privileges; nothing existing is removed or changed, so the image of
-- the U3 keeps working on this schema with the new tables unused. Lamports and base units are bigint, never floating
-- point; instants are timestamptz (UTC). No column holds an address: the unsigned message and the signed transaction
-- (which contain the bot wallet and, in SIGNED, the main wallet) are cleared when the order reaches a final state.

--changeset memetrade:0019-bot-market
-- BR1.1, Q1: the market of a bot is chosen at creation and never changes; every existing bot stays PAPER.
ALTER TABLE bot ADD COLUMN market varchar(8) NOT NULL DEFAULT 'PAPER';
ALTER TABLE bot ADD CONSTRAINT bot_market_ck CHECK (market IN ('PAPER', 'REAL'));
--rollback ALTER TABLE bot DROP CONSTRAINT bot_market_ck; ALTER TABLE bot DROP COLUMN market;

--changeset memetrade:0019-bot-market-immutable splitStatements:false
CREATE OR REPLACE FUNCTION bot_market_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.market IS DISTINCT FROM OLD.market THEN
        RAISE EXCEPTION 'bot: the market never changes after the creation'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER bot_market_immutable BEFORE UPDATE OF market ON bot
    FOR EACH ROW EXECUTE FUNCTION bot_market_immutable();
--rollback DROP TRIGGER bot_market_immutable ON bot; DROP FUNCTION bot_market_immutable();

--changeset memetrade:0019-real-decision-request
-- BR1.2, BR2.1, entities.md RealDecisionRequest: the decision of a REAL bot recorded by the cycle (M6 recordDecision)
-- and consumed by the worker. One per decision of the cycle.
CREATE TABLE real_decision_request (
    decision_request_id uuid         PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenant (tenant_id),
    user_id             uuid         NOT NULL REFERENCES app_user (user_id),
    bot_id              uuid         NOT NULL,
    decision_id         uuid         NOT NULL,
    approval_id         uuid         NULL,
    side                varchar(4)   NOT NULL,
    mint                varchar(44)  NOT NULL,
    input_lamports      bigint       NULL,
    token_base_units    bigint       NULL,
    reason              varchar(500) NOT NULL,
    state               varchar(12)  NOT NULL DEFAULT 'PENDING',
    discard_reason      varchar(32)  NULL,
    created_at          timestamptz  NOT NULL,
    processed_at        timestamptz  NULL,
    CONSTRAINT uq_real_decision_request_decision UNIQUE (decision_id),
    CONSTRAINT real_decision_request_side_ck CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT real_decision_request_amount_ck CHECK (
        (side = 'BUY' AND input_lamports > 0 AND token_base_units IS NULL AND approval_id IS NOT NULL)
        OR (side = 'SELL' AND token_base_units > 0 AND input_lamports IS NULL AND approval_id IS NULL)),
    CONSTRAINT real_decision_request_state_ck CHECK (state IN ('PENDING', 'PROCESSED', 'DISCARDED')),
    CONSTRAINT real_decision_request_discard_ck CHECK ((state = 'DISCARDED') = (discard_reason IS NOT NULL)),
    CONSTRAINT real_decision_request_discard_value_ck CHECK (discard_reason IS NULL OR discard_reason IN (
        'RESERVATION_EXPIRED', 'BOT_NOT_OWNED', 'ORDER_IN_PROGRESS', 'STATE_CHANGED', 'LIQUIDATION_ACTIVE',
        'REAL_DISABLED')),
    CONSTRAINT real_decision_request_processed_ck CHECK ((state = 'PENDING') = (processed_at IS NULL))
);
-- U4-NFR3.8: the queue of the consumer, oldest first; the purge of the closed decisions.
CREATE INDEX real_decision_request_queue_idx ON real_decision_request (state, created_at);
CREATE INDEX real_decision_request_purge_idx ON real_decision_request (processed_at) WHERE state <> 'PENDING';
--rollback DROP TABLE real_decision_request;

--changeset memetrade:0019-real-order
-- entities.md RealOrder, BR2 to BR8: the REAL order with its recorded states. Every transition is a compare-and-set on
-- the expected state (reliability-design.md); the lease (lease_owner, lease_until) belongs to the worker step that
-- holds the order. fee_payer keeps only the kind of wallet, never its address.
CREATE TABLE real_order (
    real_order_id           uuid         PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenant (tenant_id),
    user_id                 uuid         NOT NULL REFERENCES app_user (user_id),
    -- Review of the U4 (Minor 5): the bot wallet of the RESERVE, fixed at creation, so the RESERVE_RELEASE of a final
    -- order never depends on the wallet still being ACTIVE (a release is never lost).
    bot_wallet_id           uuid         NULL,
    bot_id                  uuid         NULL,
    liquidation_id          uuid         NULL,
    decision_request_id     uuid         NULL,
    origin                  varchar(20)  NOT NULL,
    idempotency_key         varchar(160) NOT NULL,
    side                    varchar(4)   NOT NULL,
    mint                    varchar(44)  NOT NULL,
    input_lamports          bigint       NULL,
    input_base_units        bigint       NULL,
    operation_mode          varchar(12)  NOT NULL,
    signing_mode            varchar(10)  NOT NULL,
    max_slippage_bps        integer      NOT NULL,
    reason                  varchar(500) NOT NULL,
    state                   varchar(12)  NOT NULL,
    failure_code            varchar(32)  NULL,
    quote_at                timestamptz  NULL,
    expected_out            bigint       NULL,
    min_out                 bigint       NULL,
    requoted                boolean      NOT NULL DEFAULT false,
    signing_deadline        timestamptz  NULL,
    signing_claimed_at      timestamptz  NULL,
    signing_requested_at    timestamptz  NULL,
    message_digest          varchar(64)  NULL,
    unsigned_message        text         NULL,
    signed_transaction      text         NULL,
    tx_signature            varchar(90)  NULL,
    last_valid_block_height bigint       NULL,
    sent_at                 timestamptz  NULL,
    out_base_units          bigint       NULL,
    fee_lamports            bigint       NULL,
    fee_payer               varchar(12)  NULL,
    -- Review of the U4 (Minor 6): the rent the bot wallet paid for an account the swap created, read from the network.
    rent_lamports           bigint       NULL,
    lease_owner             varchar(64)  NULL,
    lease_until             timestamptz  NULL,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT uq_real_order_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_real_order_tx_signature UNIQUE (tx_signature),
    CONSTRAINT real_order_origin_ck CHECK (origin IN ('BOT', 'USER_LIQUIDATION')),
    CONSTRAINT real_order_origin_shape_ck CHECK (
        (origin = 'BOT' AND bot_id IS NOT NULL AND liquidation_id IS NULL)
        OR (origin = 'USER_LIQUIDATION' AND liquidation_id IS NOT NULL AND bot_id IS NULL AND side = 'SELL')),
    CONSTRAINT real_order_side_ck CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT real_order_amount_ck CHECK (
        (side = 'BUY' AND input_lamports > 0 AND input_base_units IS NULL)
        OR (side = 'SELL' AND input_base_units > 0 AND input_lamports IS NULL)),
    CONSTRAINT real_order_mode_ck CHECK (operation_mode IN ('SIGNED', 'AUTO_EXITS', 'AUTO_FULL')),
    CONSTRAINT real_order_signing_mode_ck CHECK (signing_mode IN ('SIGNED', 'AUTO_FULL')),
    CONSTRAINT real_order_slippage_ck CHECK (max_slippage_bps BETWEEN 1 AND 500),
    CONSTRAINT real_order_state_ck CHECK (state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'CONFIRMED', 'FAILED',
        'AMBIGUOUS', 'CANCELLED')),
    CONSTRAINT real_order_failure_ck CHECK ((state IN ('FAILED', 'CANCELLED')) = (failure_code IS NOT NULL)),
    CONSTRAINT real_order_failure_value_ck CHECK (failure_code IS NULL OR failure_code IN ('QUOTE_EXPIRED',
        'UPSTREAM_UNAVAILABLE', 'SIGNING_REFUSED', 'SLIPPAGE_EXCEEDED', 'BLOCKHASH_EXPIRED', 'CHAIN_REJECTED',
        'SIGNING_DEADLINE_EXPIRED', 'USER_REJECTED', 'MODE_LOWERED', 'REAL_DISABLED', 'PAUSED', 'KILL_SWITCH',
        'RISK_REJECTED', 'ROUTE_TOO_LARGE', 'LIQUIDATION', 'NO_ROUTE')),
    CONSTRAINT real_order_signed_ck CHECK (
        state NOT IN ('SIGNED', 'SENT', 'AMBIGUOUS', 'CONFIRMED') OR tx_signature IS NOT NULL),
    CONSTRAINT real_order_fee_payer_ck CHECK (fee_payer IS NULL OR fee_payer IN ('BOT_WALLET', 'MAIN_WALLET')),
    CONSTRAINT real_order_amounts_ck CHECK (
        (expected_out IS NULL OR expected_out >= 0) AND (min_out IS NULL OR min_out >= 0)
        AND (out_base_units IS NULL OR out_base_units >= 0) AND (fee_lamports IS NULL OR fee_lamports >= 0)
        AND (rent_lamports IS NULL OR rent_lamports >= 0))
);
-- BR2.2, U4-NFR3.8: at most one order in progress per bot, and one sale in progress per person and mint (the sale of
-- the bot and the one of the liquidation never sell the same token together).
CREATE UNIQUE INDEX uq_real_order_bot_in_progress ON real_order (bot_id)
    WHERE bot_id IS NOT NULL AND state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS');
CREATE UNIQUE INDEX uq_real_order_sale_in_progress ON real_order (user_id, mint)
    WHERE side = 'SELL' AND state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS');
-- U4-NFR3.8: the list of the person, the steps of the worker, the gauges and the purge.
CREATE INDEX real_order_user_created_idx ON real_order (user_id, created_at DESC);
CREATE INDEX real_order_state_lease_idx ON real_order (state, lease_until);
CREATE INDEX real_order_sent_idx ON real_order (sent_at) WHERE state = 'SENT';
CREATE INDEX real_order_ambiguous_idx ON real_order (updated_at) WHERE state = 'AMBIGUOUS';
CREATE INDEX real_order_liquidation_idx ON real_order (liquidation_id) WHERE liquidation_id IS NOT NULL;
CREATE INDEX real_order_purge_idx ON real_order (created_at);
--rollback DROP TABLE real_order;

--changeset memetrade:0019-real-order-attempt
-- entities.md OrderAttempt, BR5.1, U4-NFR4.4: every send of the SAME signed transaction. The intent (PENDING) is
-- written with SENT, before the call to the network; the outcome only after it. Only the outcome is kept, never the
-- answer of the provider.
CREATE TABLE real_order_attempt (
    attempt_id    uuid        PRIMARY KEY,
    real_order_id uuid        NOT NULL REFERENCES real_order (real_order_id),
    tenant_id     uuid        NOT NULL REFERENCES tenant (tenant_id),
    sent_at       timestamptz NOT NULL,
    outcome       varchar(12) NOT NULL DEFAULT 'PENDING',
    resolved_at   timestamptz NULL,
    CONSTRAINT real_order_attempt_outcome_ck CHECK (outcome IN ('PENDING', 'ACCEPTED', 'RPC_ERROR', 'REJECTED')),
    CONSTRAINT real_order_attempt_resolved_ck CHECK ((outcome = 'PENDING') = (resolved_at IS NULL))
);
CREATE INDEX real_order_attempt_order_idx ON real_order_attempt (real_order_id);
CREATE INDEX real_order_attempt_purge_idx ON real_order_attempt (sent_at);
--rollback DROP TABLE real_order_attempt;

--changeset memetrade:0019-real-liquidation
-- entities.md Liquidation, BR8.1, BR8.2: "vender tudo e sacar". remaining holds only mints, quantities and codes.
CREATE TABLE real_liquidation (
    liquidation_id uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant (tenant_id),
    user_id        uuid        NOT NULL REFERENCES app_user (user_id),
    state          varchar(12) NOT NULL,
    withdrawal_id  uuid        NULL,
    remaining      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT real_liquidation_state_ck CHECK (state IN ('SELLING', 'WITHDRAWING', 'DONE', 'PARTIAL')),
    CONSTRAINT real_liquidation_remaining_ck CHECK (jsonb_typeof(remaining) = 'array')
);
-- BR8.1: at most one liquidation in SELLING or WITHDRAWING per person.
CREATE UNIQUE INDEX uq_real_liquidation_active ON real_liquidation (user_id) WHERE state IN ('SELLING', 'WITHDRAWING');
CREATE INDEX real_liquidation_purge_idx ON real_liquidation (created_at);
--rollback DROP TABLE real_liquidation;

--changeset memetrade:0019-real-upstream-health
-- entities.md UpstreamHealth, BR9.1, Q14: consecutive failures per person and provider, and since when each provider
-- is healthy (the row of the provider itself has the nil uuid as subject). No history: one row, updated.
CREATE TABLE real_upstream_health (
    subject_id           uuid        NOT NULL,
    provider             varchar(12) NOT NULL,
    tenant_id            uuid        NULL,
    consecutive_failures integer     NOT NULL DEFAULT 0,
    healthy_since        timestamptz NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT real_upstream_health_pk PRIMARY KEY (subject_id, provider),
    CONSTRAINT real_upstream_health_provider_ck CHECK (provider IN ('JUPITER', 'RPC', 'TXSIGNER')),
    CONSTRAINT real_upstream_health_failures_ck CHECK (consecutive_failures >= 0)
);
--rollback DROP TABLE real_upstream_health;

--changeset memetrade:0019-real-execution-purge-run
-- U4-NFR3.9: one row per round of the purge of the REAL execution, written by the retention login only; the same
-- shape as real_risk_purge_run (0018), which this migration never touches.
CREATE TABLE real_execution_purge_run (
    run_id        uuid        PRIMARY KEY,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    cutoff        timestamptz NOT NULL,
    deleted_count bigint      NOT NULL,
    batches       integer     NOT NULL,
    result        varchar(16) NOT NULL,
    failure_cause varchar(64) NULL,
    CONSTRAINT real_execution_purge_run_result_ck CHECK (result IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT real_execution_purge_run_counts_ck CHECK (deleted_count >= 0 AND batches >= 0),
    CONSTRAINT real_execution_purge_run_cause_ck CHECK (result = 'FAILED' OR failure_cause IS NULL)
);
CREATE INDEX real_execution_purge_run_started_idx ON real_execution_purge_run (started_at DESC);
--rollback DROP TABLE real_execution_purge_run;

--changeset memetrade:0019-real-execution-triggers splitStatements:false
-- infrastructure-specification.md "Banco": the triggers of the mutable columns, of the final states, of age and of
-- TRUNCATE, for EVERY login (not SECURITY DEFINER). Messages never carry row values.
CREATE OR REPLACE FUNCTION real_execution_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- reliability-design.md, scalability-design.md: only the listed columns of an order change; the state moves only along
-- the state machine; a final state never changes, and reaching it clears the message and the signed transaction.
CREATE OR REPLACE FUNCTION real_order_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    mutable text[] := ARRAY['state', 'failure_code', 'lease_owner', 'lease_until', 'signing_claimed_at',
        'signing_requested_at', 'quote_at', 'expected_out', 'min_out', 'requoted', 'signing_deadline',
        'message_digest', 'unsigned_message', 'signed_transaction', 'tx_signature', 'last_valid_block_height',
        'sent_at', 'out_base_units', 'fee_lamports', 'fee_payer', 'rent_lamports', 'updated_at'];
BEGIN
    IF (to_jsonb(NEW) - mutable) <> (to_jsonb(OLD) - mutable) THEN
        RAISE EXCEPTION 'real_order: the identity of an order never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.state IN ('CONFIRMED', 'FAILED', 'CANCELLED') THEN
        IF (to_jsonb(NEW) - 'unsigned_message' - 'signed_transaction' - 'updated_at')
               <> (to_jsonb(OLD) - 'unsigned_message' - 'signed_transaction' - 'updated_at')
           OR NEW.unsigned_message IS NOT NULL OR NEW.signed_transaction IS NOT NULL THEN
            RAISE EXCEPTION 'real_order: a final order never changes'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.state <> OLD.state AND NOT (OLD.state || '>' || NEW.state) = ANY (ARRAY[
            'PROPOSED>QUOTED', 'PROPOSED>FAILED', 'PROPOSED>CANCELLED',
            'QUOTED>SIGNED', 'QUOTED>FAILED', 'QUOTED>CANCELLED',
            'SIGNED>SENT', 'SIGNED>CANCELLED',
            'SENT>CONFIRMED', 'SENT>FAILED', 'SENT>AMBIGUOUS',
            'AMBIGUOUS>CONFIRMED', 'AMBIGUOUS>FAILED']) THEN
        RAISE EXCEPTION 'real_order: % to % is not a transition of the state machine', OLD.state, NEW.state
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.state IN ('CONFIRMED', 'FAILED', 'CANCELLED') THEN
        NEW.unsigned_message := NULL;
        NEW.signed_transaction := NULL;
    END IF;
    RETURN NEW;
END;
$$;

-- BR2.1: a decision request is closed once (PENDING to PROCESSED or DISCARDED) and keeps its identity.
CREATE OR REPLACE FUNCTION real_decision_request_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('PROCESSED', 'DISCARDED')
       OR (to_jsonb(NEW) - 'state' - 'discard_reason' - 'processed_at')
          <> (to_jsonb(OLD) - 'state' - 'discard_reason' - 'processed_at') THEN
        RAISE EXCEPTION 'real_decision_request: only PENDING -> PROCESSED or DISCARDED is allowed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

-- U4-NFR4.4: the outcome of a send is written once.
CREATE OR REPLACE FUNCTION real_order_attempt_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.outcome <> 'PENDING' OR NEW.outcome = 'PENDING'
       OR (to_jsonb(NEW) - 'outcome' - 'resolved_at') <> (to_jsonb(OLD) - 'outcome' - 'resolved_at') THEN
        RAISE EXCEPTION 'real_order_attempt: the outcome of a send is written once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

-- BR8.1, BR8.2: SELLING -> WITHDRAWING -> DONE or PARTIAL; only state, withdrawal_id, remaining and updated_at change.
CREATE OR REPLACE FUNCTION real_liquidation_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'state' - 'withdrawal_id' - 'remaining' - 'updated_at')
           <> (to_jsonb(OLD) - 'state' - 'withdrawal_id' - 'remaining' - 'updated_at')
       OR OLD.state IN ('DONE', 'PARTIAL')
       OR (NEW.state <> OLD.state AND NOT (OLD.state || '>' || NEW.state) = ANY (ARRAY[
            'SELLING>WITHDRAWING', 'WITHDRAWING>DONE', 'WITHDRAWING>PARTIAL'])) THEN
        RAISE EXCEPTION 'real_liquidation: only SELLING -> WITHDRAWING -> DONE or PARTIAL is allowed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

-- U4-NFR3.9: a row is deleted only by a member of risk_retention and only when the instant in the column named by the
-- first argument is older than the interval of the second argument (5 years for orders, sends and liquidations).
CREATE OR REPLACE FUNCTION real_execution_age_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER') THEN
        RAISE EXCEPTION '%: DELETE is allowed only to the risk retention role', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (to_jsonb(OLD) ->> TG_ARGV[0])::timestamptz >= now() - TG_ARGV[1]::interval THEN
        RAISE EXCEPTION '%: only rows older than % are deleted', TG_TABLE_NAME, TG_ARGV[1]
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

-- U4-NFR3.9: a decision request is deleted only closed and 13 months after it was closed; a PENDING one never.
CREATE OR REPLACE FUNCTION real_decision_request_age_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER') OR OLD.state = 'PENDING'
       OR OLD.processed_at >= now() - interval '13 months' THEN
        RAISE EXCEPTION 'real_decision_request: only closed rows older than 13 months are deleted'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION real_execution_purge_run_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'real_execution_purge_run is insert-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER')
       OR OLD.started_at >= now() - interval '90 days' THEN
        RAISE EXCEPTION 'real_execution_purge_run: only rows older than 90 days are deleted, by the retention role'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER real_order_guard BEFORE UPDATE ON real_order
    FOR EACH ROW EXECUTE FUNCTION real_order_guard();
CREATE TRIGGER real_order_age BEFORE DELETE ON real_order
    FOR EACH ROW EXECUTE FUNCTION real_execution_age_guard('created_at', '5 years');
CREATE TRIGGER real_order_no_truncate BEFORE TRUNCATE ON real_order
    FOR EACH STATEMENT EXECUTE FUNCTION real_execution_reject_change();

CREATE TRIGGER real_decision_request_guard BEFORE UPDATE ON real_decision_request
    FOR EACH ROW EXECUTE FUNCTION real_decision_request_guard();
CREATE TRIGGER real_decision_request_age BEFORE DELETE ON real_decision_request
    FOR EACH ROW EXECUTE FUNCTION real_decision_request_age_guard();
CREATE TRIGGER real_decision_request_no_truncate BEFORE TRUNCATE ON real_decision_request
    FOR EACH STATEMENT EXECUTE FUNCTION real_execution_reject_change();

CREATE TRIGGER real_order_attempt_guard BEFORE UPDATE ON real_order_attempt
    FOR EACH ROW EXECUTE FUNCTION real_order_attempt_guard();
CREATE TRIGGER real_order_attempt_age BEFORE DELETE ON real_order_attempt
    FOR EACH ROW EXECUTE FUNCTION real_execution_age_guard('sent_at', '5 years');
CREATE TRIGGER real_order_attempt_no_truncate BEFORE TRUNCATE ON real_order_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION real_execution_reject_change();

CREATE TRIGGER real_liquidation_guard BEFORE UPDATE ON real_liquidation
    FOR EACH ROW EXECUTE FUNCTION real_liquidation_guard();
CREATE TRIGGER real_liquidation_age BEFORE DELETE ON real_liquidation
    FOR EACH ROW EXECUTE FUNCTION real_execution_age_guard('created_at', '5 years');
CREATE TRIGGER real_liquidation_no_truncate BEFORE TRUNCATE ON real_liquidation
    FOR EACH STATEMENT EXECUTE FUNCTION real_execution_reject_change();

CREATE TRIGGER real_upstream_health_no_truncate BEFORE TRUNCATE ON real_upstream_health
    FOR EACH STATEMENT EXECUTE FUNCTION real_execution_reject_change();

CREATE TRIGGER real_execution_purge_run_guard BEFORE UPDATE OR DELETE ON real_execution_purge_run
    FOR EACH ROW EXECUTE FUNCTION real_execution_purge_run_guard();
CREATE TRIGGER real_execution_purge_run_no_truncate BEFORE TRUNCATE ON real_execution_purge_run
    FOR EACH STATEMENT EXECUTE FUNCTION real_execution_purge_run_guard();
--rollback DROP TRIGGER real_execution_purge_run_no_truncate ON real_execution_purge_run; DROP TRIGGER real_execution_purge_run_guard ON real_execution_purge_run; DROP TRIGGER real_upstream_health_no_truncate ON real_upstream_health; DROP TRIGGER real_liquidation_no_truncate ON real_liquidation; DROP TRIGGER real_liquidation_age ON real_liquidation; DROP TRIGGER real_liquidation_guard ON real_liquidation; DROP TRIGGER real_order_attempt_no_truncate ON real_order_attempt; DROP TRIGGER real_order_attempt_age ON real_order_attempt; DROP TRIGGER real_order_attempt_guard ON real_order_attempt; DROP TRIGGER real_decision_request_no_truncate ON real_decision_request; DROP TRIGGER real_decision_request_age ON real_decision_request; DROP TRIGGER real_decision_request_guard ON real_decision_request; DROP TRIGGER real_order_no_truncate ON real_order; DROP TRIGGER real_order_age ON real_order; DROP TRIGGER real_order_guard ON real_order; DROP FUNCTION real_execution_purge_run_guard(); DROP FUNCTION real_decision_request_age_guard(); DROP FUNCTION real_execution_age_guard(); DROP FUNCTION real_liquidation_guard(); DROP FUNCTION real_order_attempt_guard(); DROP FUNCTION real_decision_request_guard(); DROP FUNCTION real_order_guard(); DROP FUNCTION real_execution_reject_change();

--changeset memetrade:0019-real-execution-privileges splitStatements:false
-- Privilege matrix of infrastructure-specification.md. The default privileges of db-bootstrap give the application
-- login SELECT, INSERT, UPDATE and DELETE on every new table; whatever its name (it comes from .env), every non-owner
-- grantee other than risk_retention loses every privilege and the INSERT holders (the application) get back exactly
-- the set of the matrix, the UPDATE limited to the mutable columns. TRUNCATE is revoked from everybody.
REVOKE ALL ON real_decision_request, real_order, real_order_attempt, real_liquidation, real_upstream_health,
    real_execution_purge_run FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('real_decision_request', 'SELECT, INSERT, UPDATE (state, discard_reason, processed_at)'),
            ('real_order', 'SELECT, INSERT, UPDATE (state, failure_code, lease_owner, lease_until,'
                || ' signing_claimed_at, signing_requested_at, quote_at, expected_out, min_out, requoted,'
                || ' signing_deadline, message_digest, unsigned_message, signed_transaction, tx_signature,'
                || ' last_valid_block_height, sent_at, out_base_units, fee_lamports, fee_payer, rent_lamports,'
                || ' updated_at)'),
            ('real_order_attempt', 'SELECT, INSERT, UPDATE (outcome, resolved_at)'),
            ('real_liquidation', 'SELECT, INSERT, UPDATE (state, withdrawal_id, remaining, updated_at)'),
            ('real_upstream_health', 'SELECT, INSERT, UPDATE'),
            ('real_execution_purge_run', '')
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
            IF spec.app_privileges <> '' AND writers IS NOT NULL AND grantee_name = ANY (writers) THEN
                EXECUTE format('GRANT %s ON %I TO %I', spec.app_privileges, spec.table_name, grantee_name);
            END IF;
        END LOOP;
        EXECUTE format('REVOKE TRUNCATE ON %I FROM CURRENT_USER', spec.table_name);
    END LOOP;
END;
$$;
GRANT SELECT, DELETE ON real_decision_request TO risk_retention;
GRANT SELECT, DELETE ON real_order TO risk_retention;
GRANT SELECT, DELETE ON real_order_attempt TO risk_retention;
GRANT SELECT, DELETE ON real_liquidation TO risk_retention;
GRANT SELECT, INSERT, DELETE ON real_execution_purge_run TO risk_retention;
--rollback REVOKE SELECT, INSERT, DELETE ON real_execution_purge_run FROM risk_retention; REVOKE SELECT, DELETE ON real_liquidation FROM risk_retention; REVOKE SELECT, DELETE ON real_order_attempt FROM risk_retention; REVOKE SELECT, DELETE ON real_order FROM risk_retention; REVOKE SELECT, DELETE ON real_decision_request FROM risk_retention;
