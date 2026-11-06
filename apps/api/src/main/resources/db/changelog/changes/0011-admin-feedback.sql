--liquibase formatted sql

-- U8 Administration and feedback (entities.md, reliability-design.md, infrastructure-specification.md). Expand only: new
-- tables, indexes, triggers and grants, and the check of user_session.role widened to accept ADMIN; no column of U1 to
-- U7 is removed or changed, so the U5 image keeps working on this schema with the new tables unused (rollback of
-- cicd-pipeline.md, MigrationsBackwardCompatIT). Applied by db-migrate with the schema OWNER after db-bootstrap created
-- the fixed group role feedback_retention. The protection does NOT rely on REVOKE (db-bootstrap grants DML on every
-- table of the owner): triggers refuse what the application login must never do, for every login.

--changeset memetrade:0011-kill-switch-state
-- Q2: the single row with the current state of the kill switch. version grows at every change of state; marking
-- reconcile_needed never changes it. reconcile_marked_at is the fencing token of reliability-design.md (R-09): written
-- with the database clock_timestamp() when someone asks for a reconciliation; the applier clears the mark only when the
-- mark is older than the start of its own application.
CREATE TABLE kill_switch_state (
    id                  smallint     PRIMARY KEY DEFAULT 1,
    state               varchar(3)   NOT NULL DEFAULT 'OFF',
    changed_by          uuid         NULL REFERENCES app_user (user_id),
    changed_at          timestamptz  NULL,
    reason              varchar(500) NULL,
    pending_bots        integer      NOT NULL DEFAULT 0,
    reconcile_needed    boolean      NOT NULL DEFAULT false,
    reconcile_marked_at timestamptz  NULL,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT kill_switch_state_single_ck CHECK (id = 1),
    CONSTRAINT kill_switch_state_state_ck CHECK (state IN ('ON', 'OFF')),
    CONSTRAINT kill_switch_state_pending_ck CHECK (pending_bots >= 0),
    CONSTRAINT kill_switch_state_version_ck CHECK (version >= 0),
    CONSTRAINT kill_switch_state_reason_ck CHECK (reason IS NULL OR char_length(btrim(reason)) >= 1)
);
INSERT INTO kill_switch_state (id, state) VALUES (1, 'OFF');
--rollback DROP TABLE kill_switch_state;

--changeset memetrade:0011-kill-switch-change
-- BR2.9: append-only history of every change of state, one row per new version, in the same transaction as the state.
CREATE TABLE kill_switch_change (
    change_id      uuid         PRIMARY KEY,
    version        bigint       NOT NULL,
    from_state     varchar(3)   NOT NULL,
    to_state       varchar(3)   NOT NULL,
    admin_user_id  uuid         NOT NULL REFERENCES app_user (user_id),
    reason         varchar(500) NOT NULL,
    changed_at     timestamptz  NOT NULL,
    correlation_id varchar(64)  NOT NULL,
    CONSTRAINT uq_kill_switch_change_version UNIQUE (version),
    CONSTRAINT kill_switch_change_states_ck CHECK (
        from_state IN ('ON', 'OFF') AND to_state IN ('ON', 'OFF') AND from_state <> to_state),
    CONSTRAINT kill_switch_change_reason_ck CHECK (char_length(btrim(reason)) >= 1),
    CONSTRAINT kill_switch_change_version_ck CHECK (version >= 1)
);
CREATE INDEX kill_switch_change_time_idx ON kill_switch_change (changed_at DESC);
--rollback DROP TABLE kill_switch_change;

--changeset memetrade:0011-feedback-entry
-- FR13.1, BR6.1 to BR6.7: a comment of a guest, stored exactly as sent (1 to 2000 code points; char_length counts code
-- points in UTF-8). Tenant and person come from the token. Kept 180 days.
CREATE TABLE feedback_entry (
    feedback_id uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (tenant_id),
    user_id     uuid        NOT NULL REFERENCES app_user (user_id),
    screen      varchar(2)  NOT NULL,
    text        text        NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT feedback_entry_screen_ck CHECK (screen IN ('S1', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7', 'S8', 'S9')),
    CONSTRAINT feedback_entry_text_ck CHECK (char_length(text) BETWEEN 1 AND 2000 AND char_length(btrim(text)) >= 1)
);
-- NFR2.5: the administrator list pages newest first by (created_at, feedback_id) without OFFSET; the purge walks the
-- same index from the oldest side. The limit per hour counts by (user_id, created_at).
CREATE INDEX feedback_entry_page_idx ON feedback_entry (created_at DESC, feedback_id DESC);
CREATE INDEX feedback_entry_person_time_idx ON feedback_entry (user_id, created_at);
--rollback DROP TABLE feedback_entry;

--changeset memetrade:0011-user-session-admin-role splitStatements:false
-- NFR10.7: the administrator session lives in user_session with role ADMIN. Widened only: BETA_USER keeps working and
-- the previous image never writes ADMIN (an ADMIN row is inert for it, R-07 of the Infrastructure Design).
ALTER TABLE user_session DROP CONSTRAINT user_session_role_ck;
ALTER TABLE user_session ADD CONSTRAINT user_session_role_ck CHECK (role IN ('BETA_USER', 'ADMIN'));
--rollback SELECT 1;

--changeset memetrade:0011-triggers splitStatements:false
-- BR2.9, NFR4.1: the history refuses UPDATE, DELETE and TRUNCATE for EVERY login (not SECURITY DEFINER). The state
-- keeps exactly one row: a second INSERT, a DELETE and a TRUNCATE are refused; UPDATE stays allowed. Messages never
-- carry row values.
CREATE OR REPLACE FUNCTION kill_switch_change_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'kill_switch_change is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER kill_switch_change_no_update_delete BEFORE UPDATE OR DELETE ON kill_switch_change
    FOR EACH ROW EXECUTE FUNCTION kill_switch_change_guard();
CREATE TRIGGER kill_switch_change_no_truncate BEFORE TRUNCATE ON kill_switch_change
    FOR EACH STATEMENT EXECUTE FUNCTION kill_switch_change_guard();

CREATE OR REPLACE FUNCTION kill_switch_state_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM kill_switch_state) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'kill_switch_state keeps exactly one row: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER kill_switch_state_single_row BEFORE INSERT OR DELETE ON kill_switch_state
    FOR EACH ROW EXECUTE FUNCTION kill_switch_state_guard();
CREATE TRIGGER kill_switch_state_no_truncate BEFORE TRUNCATE ON kill_switch_state
    FOR EACH STATEMENT EXECUTE FUNCTION kill_switch_state_guard();

-- BR6.7, NFR4.1: a comment is never edited; it is deleted only by a member of the group role feedback_retention (the
-- retention login of the daily purge). TRUNCATE is refused everywhere.
CREATE OR REPLACE FUNCTION feedback_entry_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'feedback_entry is never edited: % is not allowed', TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT pg_has_role(current_user, 'feedback_retention', 'MEMBER') THEN
        RAISE EXCEPTION 'feedback_entry: DELETE is allowed only to the feedback retention role'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER feedback_entry_guard BEFORE UPDATE OR DELETE ON feedback_entry
    FOR EACH ROW EXECUTE FUNCTION feedback_entry_guard();
CREATE TRIGGER feedback_entry_no_truncate BEFORE TRUNCATE ON feedback_entry
    FOR EACH STATEMENT EXECUTE FUNCTION feedback_entry_guard();
--rollback DROP TRIGGER feedback_entry_no_truncate ON feedback_entry; DROP TRIGGER feedback_entry_guard ON feedback_entry; DROP FUNCTION feedback_entry_guard(); DROP TRIGGER kill_switch_state_no_truncate ON kill_switch_state; DROP TRIGGER kill_switch_state_single_row ON kill_switch_state; DROP FUNCTION kill_switch_state_guard(); DROP TRIGGER kill_switch_change_no_truncate ON kill_switch_change; DROP TRIGGER kill_switch_change_no_update_delete ON kill_switch_change; DROP FUNCTION kill_switch_change_guard();

--changeset memetrade:0011-privileges splitStatements:false
-- infrastructure-specification.md (R-01): only the group role feedback_retention reads and deletes comments for the
-- purge; the application keeps the default privileges of db-bootstrap and is held back by the triggers above.
GRANT SELECT, DELETE ON feedback_entry TO feedback_retention;
--rollback REVOKE SELECT, DELETE ON feedback_entry FROM feedback_retention;
