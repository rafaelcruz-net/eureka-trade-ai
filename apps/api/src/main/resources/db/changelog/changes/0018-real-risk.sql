--liquibase formatted sql

-- 261113-trading-real-bot, U3 u3-real-risk (entities.md, scalability-design.md, infrastructure-specification.md).
-- Expand only: new tables, indexes, checks, triggers and privileges, plus one new column with a default on
-- real_preferences (M1 pausedSince); nothing existing is removed or changed, so the image of the U2 keeps working on
-- this schema with the new tables unused (rollback of infrastructure-specification.md). Lamports and token base units
-- are bigint, never floating point (BR7.2); instants are timestamptz (UTC). The tables hold internal ids, mints and
-- amounts only, never an address nor a name (U3-NFR3.7). Every row is REAL; nothing here is read by the PAPER barrier.

--changeset memetrade:0018-real-preferences-paused-since
-- M1 (BR4.5, entities.md RealStateView.pausedSince): the start of each active pause, by reason, as ISO-8601 UTC text.
-- Kept by the BotWallet in the same statement that changes paused_reasons.
ALTER TABLE real_preferences ADD COLUMN paused_since jsonb NOT NULL DEFAULT '{}'::jsonb;
--rollback ALTER TABLE real_preferences DROP COLUMN paused_since;

--changeset memetrade:0018-real-risk-evaluation
-- BR7.1, BR6.1, entities.md RealRiskEvaluation: every REAL evaluation, approved or rejected. decision_id is unique when
-- present: a repeated evaluation of a decision returns the recorded one (U3-NFR4.2). evaluated_day is the UTC day of
-- evaluated_at, for the count of BR4.2. figures holds only integers of the evaluated wallet (U3-NFR6.3).
CREATE TABLE real_risk_evaluation (
    evaluation_id    uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant (tenant_id),
    user_id          uuid        NOT NULL REFERENCES app_user (user_id),
    bot_id           uuid        NULL,
    decision_id      uuid        NULL,
    origin           varchar(20) NOT NULL,
    side             varchar(4)  NOT NULL,
    chain            varchar(64) NOT NULL,
    mint             varchar(44) NOT NULL,
    input_lamports   bigint      NULL,
    token_base_units bigint      NULL,
    outcome          varchar(8)  NOT NULL,
    code             varchar(32) NULL,
    detail           varchar(32) NULL,
    detail_mint      varchar(44) NULL,
    figures          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    approval_id      uuid        NULL,
    evaluated_at     timestamptz NOT NULL,
    evaluated_day    date        NOT NULL,
    CONSTRAINT uq_real_risk_evaluation_decision UNIQUE (decision_id),
    CONSTRAINT real_risk_evaluation_origin_ck CHECK (origin IN ('BOT', 'USER_LIQUIDATION')),
    CONSTRAINT real_risk_evaluation_side_ck CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT real_risk_evaluation_outcome_ck CHECK (outcome IN ('APPROVED', 'REJECTED')),
    CONSTRAINT real_risk_evaluation_code_ck CHECK ((outcome = 'APPROVED') = (code IS NULL AND detail IS NULL)),
    CONSTRAINT real_risk_evaluation_code_value_ck CHECK (code IS NULL OR code IN ('REAL_PAUSED',
        'SIMULATED_SOURCE_ACTIVE', 'TOKEN_NOT_ELIGIBLE', 'STALE_DATA', 'REAL_LIMIT_EXCEEDED', 'REAL_CAP_EXCEEDED')),
    CONSTRAINT real_risk_evaluation_detail_value_ck CHECK (detail IS NULL OR detail IN ('NONE', 'NOT_RELEASED',
        'REAL_DISABLED', 'NO_BOT_WALLET', 'PAUSED_USER', 'PAUSED_DAILY_LOSS', 'PAUSED_ADMIN', 'PAUSED_DIVERGENCE',
        'PAUSED_KILL_SWITCH', 'PAUSED_UPSTREAM', 'NO_ASSESSMENT', 'BLOCKED', 'SIGNAL_STALE', 'SIGNAL_UNKNOWN',
        'POSITION_PRICE_UNKNOWN', 'ORDER_SIZE', 'DAILY_ORDER_COUNT', 'DAILY_LOSS', 'CAP', 'FREE_BALANCE')),
    CONSTRAINT real_risk_evaluation_bot_ck CHECK (origin <> 'BOT' OR (bot_id IS NOT NULL AND decision_id IS NOT NULL)),
    CONSTRAINT real_risk_evaluation_amount_ck CHECK (
        (side = 'BUY' AND origin = 'BOT' AND input_lamports > 0)
        OR (side = 'SELL' AND token_base_units > 0)),
    CONSTRAINT real_risk_evaluation_approval_ck CHECK (
        approval_id IS NULL OR (outcome = 'APPROVED' AND side = 'BUY')),
    CONSTRAINT real_risk_evaluation_day_ck CHECK (evaluated_day = (evaluated_at AT TIME ZONE 'UTC')::date),
    CONSTRAINT real_risk_evaluation_figures_ck CHECK (jsonb_typeof(figures) = 'object')
);
-- U3-NFR3.6: the count of the approved buys of a bot in one UTC day (BR4.2).
CREATE INDEX real_risk_evaluation_bot_day_idx ON real_risk_evaluation (bot_id, evaluated_day, side, outcome);
-- U3-NFR3.7: the selection of the purge by age.
CREATE INDEX real_risk_evaluation_purge_idx ON real_risk_evaluation (evaluated_at);
--rollback DROP TABLE real_risk_evaluation;

--changeset memetrade:0018-real-risk-reservation
-- BR6.2, entities.md GateReservation: the reservation of an approved buy until the U4 takes it over (HANDED_OVER) or
-- it expires (EXPIRED). One per decision. Only state and closed_at ever change (trigger below).
CREATE TABLE real_risk_reservation (
    approval_id    uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant (tenant_id),
    user_id        uuid        NOT NULL REFERENCES app_user (user_id),
    bot_wallet_id  uuid        NOT NULL,
    bot_id         uuid        NOT NULL,
    decision_id    uuid        NOT NULL,
    mint           varchar(44) NOT NULL,
    input_lamports bigint      NOT NULL,
    state          varchar(16) NOT NULL DEFAULT 'OPEN',
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    closed_at      timestamptz NULL,
    CONSTRAINT uq_real_risk_reservation_decision UNIQUE (decision_id),
    CONSTRAINT real_risk_reservation_amount_ck CHECK (input_lamports > 0),
    CONSTRAINT real_risk_reservation_state_ck CHECK (state IN ('OPEN', 'HANDED_OVER', 'EXPIRED')),
    CONSTRAINT real_risk_reservation_closed_ck CHECK ((state = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT real_risk_reservation_expiry_ck CHECK (expires_at > created_at)
);
-- U3-NFR3.6: the open reservations of a wallet (BR5.1), and the OPEN rows of the expiry job and of the gauge.
CREATE INDEX real_risk_reservation_wallet_idx ON real_risk_reservation (bot_wallet_id, state);
CREATE INDEX real_risk_reservation_open_idx ON real_risk_reservation (expires_at) WHERE state = 'OPEN';
CREATE INDEX real_risk_reservation_purge_idx ON real_risk_reservation (created_at) WHERE state <> 'OPEN';
--rollback DROP TABLE real_risk_reservation;

--changeset memetrade:0018-real-risk-day
-- BR4.4, entities.md DaySnapshot: one photo per wallet and UTC day, never changed.
CREATE TABLE real_risk_day (
    tenant_id            uuid        NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id        uuid        NOT NULL,
    day                  date        NOT NULL,
    start_value_lamports bigint      NOT NULL,
    start_cap_lamports   bigint      NOT NULL,
    taken_at             timestamptz NOT NULL,
    taken_by             varchar(20) NOT NULL,
    CONSTRAINT real_risk_day_pk PRIMARY KEY (bot_wallet_id, day),
    CONSTRAINT real_risk_day_values_ck CHECK (start_value_lamports >= 0 AND start_cap_lamports >= 0),
    CONSTRAINT real_risk_day_taken_by_ck CHECK (taken_by IN ('DAY_JOB', 'FIRST_EVALUATION'))
);
CREATE INDEX real_risk_day_purge_idx ON real_risk_day (taken_at);
--rollback DROP TABLE real_risk_day;

--changeset memetrade:0018-real-blocked-token
-- BR2.3, entities.md BlockedToken: the global block list of the administrator; changed only through M5.
CREATE TABLE real_blocked_token (
    chain      varchar(64)  NOT NULL,
    mint       varchar(44)  NOT NULL,
    reason     varchar(500) NOT NULL,
    blocked_by uuid         NOT NULL,
    blocked_at timestamptz  NOT NULL,
    CONSTRAINT real_blocked_token_pk PRIMARY KEY (chain, mint),
    CONSTRAINT real_blocked_token_reason_ck CHECK (length(btrim(reason)) BETWEEN 1 AND 500)
);
--rollback DROP TABLE real_blocked_token;

--changeset memetrade:0018-real-risk-purge-run
-- infrastructure-specification.md "Registro do expurgo": one row per round of the REAL purge, written by the retention
-- login only; the same shape as risk_purge_run (0009), which this migration never touches.
CREATE TABLE real_risk_purge_run (
    run_id        uuid        PRIMARY KEY,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    cutoff        timestamptz NOT NULL,
    deleted_count bigint      NOT NULL,
    batches       integer     NOT NULL,
    result        varchar(16) NOT NULL,
    failure_cause varchar(64) NULL,
    CONSTRAINT real_risk_purge_run_result_ck CHECK (result IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT real_risk_purge_run_counts_ck CHECK (deleted_count >= 0 AND batches >= 0),
    CONSTRAINT real_risk_purge_run_cause_ck CHECK (result = 'FAILED' OR failure_cause IS NULL)
);
CREATE INDEX real_risk_purge_run_started_idx ON real_risk_purge_run (started_at DESC);
--rollback DROP TABLE real_risk_purge_run;

--changeset memetrade:0018-real-risk-triggers splitStatements:false
-- infrastructure-specification.md "Gatilhos", for EVERY login (not SECURITY DEFINER: current_user is whoever runs the
-- statement). Messages never carry row values.
CREATE OR REPLACE FUNCTION real_risk_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- U3-NFR3.7: a row is deleted only by a member of risk_retention and only when the instant in the column named by the
-- trigger argument is more than 13 months before the database now(). This also protects the current and the
-- previous UTC day, on which the count of BR4.2 and the repetition by decision_id depend.
CREATE OR REPLACE FUNCTION real_risk_age_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER') THEN
        RAISE EXCEPTION '%: DELETE is allowed only to the risk retention role', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (to_jsonb(OLD) ->> TG_ARGV[0])::timestamptz >= now() - interval '13 months' THEN
        RAISE EXCEPTION '%: only rows older than 13 months are deleted', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

-- BR6.2: a reservation changes once, from OPEN to HANDED_OVER or EXPIRED with its closed_at; no other column changes.
CREATE OR REPLACE FUNCTION real_risk_reservation_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'OPEN' OR NEW.state NOT IN ('HANDED_OVER', 'EXPIRED') OR NEW.closed_at IS NULL
       OR (to_jsonb(NEW) - 'state' - 'closed_at') <> (to_jsonb(OLD) - 'state' - 'closed_at') THEN
        RAISE EXCEPTION 'real_risk_reservation: only OPEN -> HANDED_OVER or EXPIRED is allowed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION real_risk_purge_run_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'real_risk_purge_run is insert-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER')
       OR OLD.started_at >= now() - interval '90 days' THEN
        RAISE EXCEPTION 'real_risk_purge_run: only rows older than 90 days are deleted, by the risk retention role'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER real_risk_evaluation_no_update BEFORE UPDATE ON real_risk_evaluation
    FOR EACH ROW EXECUTE FUNCTION real_risk_reject_change();
CREATE TRIGGER real_risk_evaluation_age BEFORE DELETE ON real_risk_evaluation
    FOR EACH ROW EXECUTE FUNCTION real_risk_age_guard('evaluated_at');
CREATE TRIGGER real_risk_evaluation_no_truncate BEFORE TRUNCATE ON real_risk_evaluation
    FOR EACH STATEMENT EXECUTE FUNCTION real_risk_reject_change();

CREATE TRIGGER real_risk_reservation_transition BEFORE UPDATE ON real_risk_reservation
    FOR EACH ROW EXECUTE FUNCTION real_risk_reservation_transition();
CREATE TRIGGER real_risk_reservation_age BEFORE DELETE ON real_risk_reservation
    FOR EACH ROW EXECUTE FUNCTION real_risk_age_guard('created_at');
CREATE TRIGGER real_risk_reservation_no_truncate BEFORE TRUNCATE ON real_risk_reservation
    FOR EACH STATEMENT EXECUTE FUNCTION real_risk_reject_change();

CREATE TRIGGER real_risk_day_no_update BEFORE UPDATE ON real_risk_day
    FOR EACH ROW EXECUTE FUNCTION real_risk_reject_change();
CREATE TRIGGER real_risk_day_age BEFORE DELETE ON real_risk_day
    FOR EACH ROW EXECUTE FUNCTION real_risk_age_guard('taken_at');
CREATE TRIGGER real_risk_day_no_truncate BEFORE TRUNCATE ON real_risk_day
    FOR EACH STATEMENT EXECUTE FUNCTION real_risk_reject_change();

CREATE TRIGGER real_blocked_token_no_update BEFORE UPDATE ON real_blocked_token
    FOR EACH ROW EXECUTE FUNCTION real_risk_reject_change();
CREATE TRIGGER real_blocked_token_no_truncate BEFORE TRUNCATE ON real_blocked_token
    FOR EACH STATEMENT EXECUTE FUNCTION real_risk_reject_change();

CREATE TRIGGER real_risk_purge_run_guard BEFORE UPDATE OR DELETE ON real_risk_purge_run
    FOR EACH ROW EXECUTE FUNCTION real_risk_purge_run_guard();
CREATE TRIGGER real_risk_purge_run_no_truncate BEFORE TRUNCATE ON real_risk_purge_run
    FOR EACH STATEMENT EXECUTE FUNCTION real_risk_purge_run_guard();
--rollback DROP TRIGGER real_risk_purge_run_no_truncate ON real_risk_purge_run; DROP TRIGGER real_risk_purge_run_guard ON real_risk_purge_run; DROP TRIGGER real_blocked_token_no_truncate ON real_blocked_token; DROP TRIGGER real_blocked_token_no_update ON real_blocked_token; DROP TRIGGER real_risk_day_no_truncate ON real_risk_day; DROP TRIGGER real_risk_day_age ON real_risk_day; DROP TRIGGER real_risk_day_no_update ON real_risk_day; DROP TRIGGER real_risk_reservation_no_truncate ON real_risk_reservation; DROP TRIGGER real_risk_reservation_age ON real_risk_reservation; DROP TRIGGER real_risk_reservation_transition ON real_risk_reservation; DROP TRIGGER real_risk_evaluation_no_truncate ON real_risk_evaluation; DROP TRIGGER real_risk_evaluation_age ON real_risk_evaluation; DROP TRIGGER real_risk_evaluation_no_update ON real_risk_evaluation; DROP FUNCTION real_risk_purge_run_guard(); DROP FUNCTION real_risk_reservation_transition(); DROP FUNCTION real_risk_age_guard(); DROP FUNCTION real_risk_reject_change();

--changeset memetrade:0018-real-risk-privileges splitStatements:false
-- Privilege matrix of infrastructure-specification.md. The default privileges of db-bootstrap give the application
-- login SELECT, INSERT, UPDATE and DELETE on every new table; whatever its name (it comes from .env), every non-owner
-- grantee other than risk_retention loses every privilege and the INSERT holders (the application) get back exactly
-- the set of the matrix. TRUNCATE is revoked from everybody, the owner included (the triggers refuse it anyway).
REVOKE ALL ON real_risk_evaluation, real_risk_reservation, real_risk_day, real_blocked_token, real_risk_purge_run
    FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('real_risk_evaluation', 'SELECT, INSERT'),
            ('real_risk_reservation', 'SELECT, INSERT, UPDATE'),
            ('real_risk_day', 'SELECT, INSERT'),
            ('real_blocked_token', 'SELECT, INSERT, DELETE'),
            ('real_risk_purge_run', '')
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
GRANT SELECT, DELETE ON real_risk_evaluation TO risk_retention;
GRANT SELECT, DELETE ON real_risk_reservation TO risk_retention;
GRANT SELECT, DELETE ON real_risk_day TO risk_retention;
GRANT SELECT, INSERT, DELETE ON real_risk_purge_run TO risk_retention;
--rollback REVOKE SELECT, INSERT, DELETE ON real_risk_purge_run FROM risk_retention; REVOKE SELECT, DELETE ON real_risk_day FROM risk_retention; REVOKE SELECT, DELETE ON real_risk_reservation FROM risk_retention; REVOKE SELECT, DELETE ON real_risk_evaluation FROM risk_retention;
