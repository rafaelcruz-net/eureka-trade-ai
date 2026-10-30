--liquibase formatted sql

-- U2 audit trail (security-design.md, performance-design.md, reliability-design.md). Applied by db-migrate with the
-- schema OWNER login after db-bootstrap created the logins and the fixed group role audit_retention. Additive only
-- (expand-contract): rolling the API back to U1 never requires undoing this file.

--changeset memetrade:0005-audit-event-recorded-at
-- recordedAt (entities.md): instant at which the relay wrote the event. Nullable expansion; the rows of U1 receive
-- their occurred_at. The append-only trigger is suspended only inside this transaction, by the owner.
ALTER TABLE audit_event ADD COLUMN recorded_at timestamptz NULL;
ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_update_delete;
UPDATE audit_event SET recorded_at = occurred_at WHERE recorded_at IS NULL;
ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_update_delete;
--rollback ALTER TABLE audit_event DROP COLUMN recorded_at;

--changeset memetrade:0005-audit-indexes
-- NFR3.1: the query pages by (occurred_at, event_id) without OFFSET, so event_id is part of both indexes and the
-- tie-break comes from the index without a separate sort. recorded_at serves only the daily count at startup.
CREATE INDEX audit_event_time_id_idx ON audit_event (occurred_at DESC, event_id DESC);
CREATE INDEX audit_event_action_time_id_idx ON audit_event (action, occurred_at DESC, event_id DESC);
CREATE INDEX audit_event_recorded_idx ON audit_event (recorded_at);
DROP INDEX audit_event_action_time_idx;
-- NFR3.3: the outbox cleanup selects delivered rows only.
CREATE INDEX audit_outbox_delivered_idx ON audit_outbox (delivered_at) WHERE delivered_at IS NOT NULL;
--rollback DROP INDEX audit_outbox_delivered_idx; CREATE INDEX audit_event_action_time_idx ON audit_event (action, occurred_at); DROP INDEX audit_event_recorded_idx; DROP INDEX audit_event_action_time_id_idx; DROP INDEX audit_event_time_id_idx;

--changeset memetrade:0005-audit-event-trigger splitStatements:false
-- NFR6.4 / BR1.3: UPDATE and TRUNCATE are always refused, even for the owner. DELETE is accepted only from a member
-- of audit_retention and only for events older than the start of the UTC day minus 12 months. Not SECURITY DEFINER:
-- current_user is whoever runs the statement. Messages never carry row values.
CREATE OR REPLACE FUNCTION audit_event_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
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

--changeset memetrade:0005-audit-event-privileges splitStatements:false
-- NFR6.2: the application keeps SELECT and INSERT on audit_event and loses UPDATE, DELETE and TRUNCATE, whatever
-- its login name (the names come from .env, so every non-owner grantee is revoked). Only the group role
-- audit_retention may read and delete, and the trigger still bounds DELETE to the retention window.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM PUBLIC;
DO $$
DECLARE
    grantee_name text;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT r.rolname
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = 'audit_event'::regclass
          AND a.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
          AND a.grantee <> c.relowner
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM %I', grantee_name);
    END LOOP;
END;
$$;
GRANT SELECT, DELETE ON audit_event TO audit_retention;
--rollback REVOKE SELECT, DELETE ON audit_event FROM audit_retention;

--changeset memetrade:0005-audit-job-run
-- Q4 of NFR Design: one row per daily job, read by the startup recovery and the health indicators.
CREATE TABLE audit_job_run (
    job_name        varchar(64) PRIMARY KEY,
    last_run_at     timestamptz NOT NULL,
    last_result     varchar(16) NOT NULL,
    last_success_at timestamptz NULL,
    last_cutoff     timestamptz NULL,
    CONSTRAINT audit_job_run_job_ck CHECK (job_name IN ('AUDIT_RETENTION_PURGE', 'AUDIT_OUTBOX_CLEANUP')),
    CONSTRAINT audit_job_run_result_ck CHECK (last_result IN ('COMPLETED', 'FAILED', 'TIME_LIMIT'))
);
--rollback DROP TABLE audit_job_run;

--changeset memetrade:0005-shedlock
-- ShedLock (JdbcTemplateLockProvider): a single run per job across instances (NFR4.6). Instants are timestamptz.
CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);
--rollback DROP TABLE shedlock;

--changeset memetrade:0005-audit-event-autovacuum
-- About 100 thousand deletions a day in a table of tens of millions of rows (infrastructure-specification.md).
ALTER TABLE audit_event SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_analyze_scale_factor = 0.005);
--rollback ALTER TABLE audit_event RESET (autovacuum_vacuum_scale_factor, autovacuum_analyze_scale_factor);
