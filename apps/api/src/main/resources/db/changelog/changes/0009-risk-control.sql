--liquibase formatted sql

-- U6 RiskControl (entities.md, security-design.md, scalability-design.md, infrastructure-specification.md). Expand
-- only: new tables, indexes, checks, triggers and privileges; nothing of U1 to U7 changes, so the U7 image keeps
-- working on this schema with the new tables unused (rollback of cicd-pipeline.md). Applied by db-migrate with the
-- schema OWNER after db-bootstrap created the fixed group role risk_retention.
-- Scales: USDC amounts numeric(38,6), unit prices numeric(38,12), token quantities numeric(38,18), percentage points
-- numeric(9,4) (values inside the MarketInputs document and the details travel as exact decimal TEXT, never floating
-- point). Every row is PAPER (NFR5.3).

--changeset memetrade:0009-order-evaluation
-- BR3.4, BR2.8: the first evaluation of each decision, approved or rejected; one per (tenant, mode, bot, decision).
CREATE TABLE order_evaluation (
    evaluation_id      uuid           PRIMARY KEY,
    tenant_id          uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id      uuid           NOT NULL REFERENCES app_user (user_id),
    mode               varchar(8)     NOT NULL DEFAULT 'PAPER',
    bot_id             uuid           NOT NULL,
    decision_id        uuid           NOT NULL,
    side               varchar(4)     NOT NULL,
    chain              varchar(64)    NOT NULL,
    token_mint         varchar(64)    NOT NULL,
    requested_quantity numeric(38,18) NOT NULL,
    limits_version     bigint         NOT NULL,
    outcome            varchar(8)     NOT NULL,
    evaluated_at       timestamptz    NOT NULL,
    CONSTRAINT uq_order_evaluation_decision UNIQUE (tenant_id, mode, bot_id, decision_id),
    CONSTRAINT order_evaluation_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT order_evaluation_side_ck CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT order_evaluation_quantity_ck CHECK (requested_quantity > 0),
    CONSTRAINT order_evaluation_version_ck CHECK (limits_version >= 1),
    CONSTRAINT order_evaluation_outcome_ck CHECK (outcome IN ('APPROVED', 'REJECTED'))
);
-- Selection of the purge batches (scalability-design.md): only approved rows, by age.
CREATE INDEX order_evaluation_purge_idx ON order_evaluation (evaluated_at) WHERE outcome = 'APPROVED';
--rollback DROP TABLE order_evaluation;

--changeset memetrade:0009-order-rejection
-- BR3.5, BR4.2: one refused order with its reasons (fixed order of BR3.1, comma separated), pause triggers, limits
-- version and the MarketInputs read in the call. The foreign key has NO "ON DELETE": an evaluation cited by any
-- rejection can never be deleted (BR4.3 as amended by the NFR Design).
CREATE TABLE order_rejection (
    rejection_id       uuid           PRIMARY KEY,
    tenant_id          uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id      uuid           NOT NULL REFERENCES app_user (user_id),
    mode               varchar(8)     NOT NULL DEFAULT 'PAPER',
    bot_id             uuid           NOT NULL,
    decision_id        uuid           NOT NULL,
    evaluation_id      uuid           NOT NULL REFERENCES order_evaluation (evaluation_id),
    side               varchar(4)     NOT NULL,
    chain              varchar(64)    NOT NULL,
    token_mint         varchar(64)    NOT NULL,
    requested_quantity numeric(38,18) NOT NULL,
    reasons            varchar(400)   NOT NULL,
    pause_triggers     varchar(32)    NOT NULL DEFAULT '',
    limits_version     bigint         NOT NULL,
    market             jsonb          NOT NULL,
    evaluated_at       timestamptz    NOT NULL,
    CONSTRAINT order_rejection_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT order_rejection_side_ck CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT order_rejection_version_ck CHECK (limits_version >= 1),
    CONSTRAINT order_rejection_reasons_ck CHECK (reasons ~ ('^(ORDER_SIZE|TOKEN_EXPOSURE|TOTAL_EXPOSURE|SLIPPAGE'
        || '|MIN_LIQUIDITY|STALE_DATA|INTERVAL|DUPLICATE|INSUFFICIENT_PAPER_BALANCE|DAILY_LOSS|DRAWDOWN)'
        || '(,(ORDER_SIZE|TOKEN_EXPOSURE|TOTAL_EXPOSURE|SLIPPAGE|MIN_LIQUIDITY|STALE_DATA|INTERVAL|DUPLICATE'
        || '|INSUFFICIENT_PAPER_BALANCE|DAILY_LOSS|DRAWDOWN))*$')),
    CONSTRAINT order_rejection_triggers_ck CHECK (
        pause_triggers IN ('', 'DAILY_LOSS', 'DRAWDOWN', 'DAILY_LOSS,DRAWDOWN')),
    CONSTRAINT order_rejection_market_ck CHECK (
        market -> 'referencePrice' IS NOT NULL AND market -> 'liquidity' IS NOT NULL
        AND market -> 'estimatedSlippagePct' IS NOT NULL AND market -> 'orderValue' IS NOT NULL)
);
-- Page of C21 (performance-design.md): filter and order straight from the index, keyset by (evaluated_at, id).
CREATE INDEX order_rejection_page_idx
    ON order_rejection (tenant_id, owner_user_id, mode, bot_id, evaluated_at DESC, rejection_id DESC);
-- The NOT EXISTS of the purge and the foreign key check on delete, without a scan of the rejections.
CREATE INDEX order_rejection_evaluation_idx ON order_rejection (evaluation_id);
--rollback DROP TABLE order_rejection;

--changeset memetrade:0009-rejection-reason-detail
-- Q10: the number that barred each reason; one detail per reason of the rejection. measured is a decimal text in the
-- scale of the unit or UNKNOWN (never zero for a missing value); limit_value is the limit, or the original evaluation
-- id for DUPLICATE ("limit" is a reserved word).
CREATE TABLE rejection_reason_detail (
    rejection_id  uuid        NOT NULL REFERENCES order_rejection (rejection_id),
    tenant_id     uuid        NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id uuid        NOT NULL REFERENCES app_user (user_id),
    mode          varchar(8)  NOT NULL DEFAULT 'PAPER',
    reason        varchar(32) NOT NULL,
    measured      varchar(64) NOT NULL,
    limit_value   varchar(64) NOT NULL,
    unit          varchar(16) NOT NULL,
    PRIMARY KEY (rejection_id, reason),
    CONSTRAINT rejection_reason_detail_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT rejection_reason_detail_reason_ck CHECK (reason IN ('ORDER_SIZE', 'TOKEN_EXPOSURE', 'TOTAL_EXPOSURE',
        'SLIPPAGE', 'MIN_LIQUIDITY', 'STALE_DATA', 'INTERVAL', 'DUPLICATE', 'INSUFFICIENT_PAPER_BALANCE', 'DAILY_LOSS',
        'DRAWDOWN')),
    CONSTRAINT rejection_reason_detail_unit_ck CHECK (unit IN ('USDC', 'PERCENT', 'SECONDS', 'REFERENCE')),
    CONSTRAINT rejection_reason_detail_measured_ck CHECK (
        measured = 'UNKNOWN' OR measured ~ '^-?[0-9]+(\.[0-9]+)?$'
        OR measured ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
);
--rollback DROP TABLE rejection_reason_detail;

--changeset memetrade:0009-risk-purge-run
-- reliability-design.md: one row per round of the purge, written by the retention login only; read by the health
-- indicator. Insert only, except the cleanup of rows older than 90 days by the round itself.
CREATE TABLE risk_purge_run (
    run_id        uuid        PRIMARY KEY,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    cutoff        timestamptz NOT NULL,
    deleted_count bigint      NOT NULL,
    batches       integer     NOT NULL,
    result        varchar(16) NOT NULL,
    failure_cause varchar(64) NULL,
    CONSTRAINT risk_purge_run_result_ck CHECK (result IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT risk_purge_run_counts_ck CHECK (deleted_count >= 0 AND batches >= 0),
    CONSTRAINT risk_purge_run_cause_ck CHECK (result = 'FAILED' OR failure_cause IS NULL)
);
CREATE INDEX risk_purge_run_started_idx ON risk_purge_run (started_at DESC);
--rollback DROP TABLE risk_purge_run;

--changeset memetrade:0009-triggers splitStatements:false
-- NFR4.3: UPDATE is refused on the four tables and DELETE on rejections and details, for EVERY login (not SECURITY
-- DEFINER: current_user is whoever runs the statement). TRUNCATE is refused everywhere. Messages never carry row values.
CREATE OR REPLACE FUNCTION risk_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER order_rejection_no_update_delete BEFORE UPDATE OR DELETE ON order_rejection
    FOR EACH ROW EXECUTE FUNCTION risk_reject_change();
CREATE TRIGGER order_rejection_no_truncate BEFORE TRUNCATE ON order_rejection
    FOR EACH STATEMENT EXECUTE FUNCTION risk_reject_change();
CREATE TRIGGER rejection_reason_detail_no_update_delete BEFORE UPDATE OR DELETE ON rejection_reason_detail
    FOR EACH ROW EXECUTE FUNCTION risk_reject_change();
CREATE TRIGGER rejection_reason_detail_no_truncate BEFORE TRUNCATE ON rejection_reason_detail
    FOR EACH STATEMENT EXECUTE FUNCTION risk_reject_change();

-- BR4.3 as amended by the NFR Design (security-design.md): an evaluation is deleted only by a member of
-- risk_retention, only when APPROVED and evaluated more than 30 days before the database now(). The foreign key of
-- order_rejection (no ON DELETE) blocks every evaluation cited by a rejection. The purge selects rows older than 31
-- days in the same statement, so every selected row passes this check.
CREATE OR REPLACE FUNCTION order_evaluation_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'order_evaluation is append-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER') THEN
        RAISE EXCEPTION 'order_evaluation: DELETE is allowed only to the risk retention role'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.outcome <> 'APPROVED' OR OLD.evaluated_at >= now() - interval '30 days' THEN
        RAISE EXCEPTION 'order_evaluation: only approved evaluations older than 30 days are deleted'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER order_evaluation_guard BEFORE UPDATE OR DELETE ON order_evaluation
    FOR EACH ROW EXECUTE FUNCTION order_evaluation_guard();
CREATE TRIGGER order_evaluation_no_truncate BEFORE TRUNCATE ON order_evaluation
    FOR EACH STATEMENT EXECUTE FUNCTION order_evaluation_guard();

-- reliability-design.md: the run log is insert only; the round itself deletes its rows older than 90 days.
CREATE OR REPLACE FUNCTION risk_purge_run_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'risk_purge_run is insert-only: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT pg_has_role(current_user, 'risk_retention', 'MEMBER')
       OR OLD.started_at >= now() - interval '90 days' THEN
        RAISE EXCEPTION 'risk_purge_run: only rows older than 90 days are deleted, by the risk retention role'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER risk_purge_run_guard BEFORE UPDATE OR DELETE ON risk_purge_run
    FOR EACH ROW EXECUTE FUNCTION risk_purge_run_guard();
CREATE TRIGGER risk_purge_run_no_truncate BEFORE TRUNCATE ON risk_purge_run
    FOR EACH STATEMENT EXECUTE FUNCTION risk_purge_run_guard();
--rollback DROP TRIGGER risk_purge_run_no_truncate ON risk_purge_run; DROP TRIGGER risk_purge_run_guard ON risk_purge_run; DROP FUNCTION risk_purge_run_guard(); DROP TRIGGER order_evaluation_no_truncate ON order_evaluation; DROP TRIGGER order_evaluation_guard ON order_evaluation; DROP FUNCTION order_evaluation_guard(); DROP TRIGGER rejection_reason_detail_no_truncate ON rejection_reason_detail; DROP TRIGGER rejection_reason_detail_no_update_delete ON rejection_reason_detail; DROP TRIGGER order_rejection_no_truncate ON order_rejection; DROP TRIGGER order_rejection_no_update_delete ON order_rejection; DROP FUNCTION risk_reject_change();

--changeset memetrade:0009-privileges splitStatements:false
-- Privilege matrix of infrastructure-specification.md (NFR4.3). The default privileges of db-bootstrap give the
-- application login SELECT, INSERT, UPDATE and DELETE on every new table; whatever its name (it comes from .env),
-- every non-owner grantee loses UPDATE, DELETE, TRUNCATE, REFERENCES and TRIGGER, keeps INSERT only on the three
-- records (never on risk_purge_run) and keeps SELECT. TRUNCATE is revoked from everybody, the owner included (the
-- triggers refuse it anyway). Only the group role risk_retention deletes evaluations and writes the run log.
REVOKE ALL ON order_evaluation, order_rejection, rejection_reason_detail, risk_purge_run FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('order_evaluation', true),
            ('order_rejection', true),
            ('rejection_reason_detail', true),
            ('risk_purge_run', false)
        ) AS s(table_name, can_insert)
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
            EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON %I FROM %I',
                           spec.table_name, grantee_name);
            IF spec.can_insert AND writers IS NOT NULL AND grantee_name = ANY (writers) THEN
                EXECUTE format('GRANT INSERT ON %I TO %I', spec.table_name, grantee_name);
            END IF;
        END LOOP;
        EXECUTE format('REVOKE TRUNCATE ON %I FROM CURRENT_USER', spec.table_name);
    END LOOP;
END;
$$;
GRANT SELECT, DELETE ON order_evaluation TO risk_retention;
GRANT SELECT (evaluation_id) ON order_rejection TO risk_retention;
GRANT SELECT, INSERT, DELETE ON risk_purge_run TO risk_retention;
--rollback REVOKE SELECT, INSERT, DELETE ON risk_purge_run FROM risk_retention; REVOKE SELECT (evaluation_id) ON order_rejection FROM risk_retention; REVOKE SELECT, DELETE ON order_evaluation FROM risk_retention;
