--liquibase formatted sql

--changeset memetrade:0004-audit-outbox
-- C9: written in the same transaction as the audited action; only AuditEventPublisher touches it.
CREATE TABLE audit_outbox (
    event_id     uuid        PRIMARY KEY,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    delivered_at timestamptz NULL
);
CREATE INDEX audit_outbox_pending_idx ON audit_outbox (created_at) WHERE delivered_at IS NULL;
--rollback DROP TABLE audit_outbox;

--changeset memetrade:0004-audit-event
-- Append-only trail (ADR-006). details never holds a secret, token, nonce or signature (BR4.3).
CREATE TABLE audit_event (
    event_id       uuid         PRIMARY KEY,
    tenant_id      uuid         NULL,
    actor          varchar(200) NOT NULL,
    actor_type     varchar(16)  NOT NULL,
    action         varchar(64)  NOT NULL,
    target_ref     varchar(200) NULL,
    correlation_id varchar(64)  NOT NULL,
    occurred_at    timestamptz  NOT NULL,
    details        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_event_actor_type_ck CHECK (actor_type IN ('PERSON', 'ADMIN', 'SYSTEM', 'ANONYMOUS'))
);
CREATE INDEX audit_event_correlation_idx ON audit_event (correlation_id);
CREATE INDEX audit_event_tenant_time_idx ON audit_event (tenant_id, occurred_at);
CREATE INDEX audit_event_action_time_idx ON audit_event (action, occurred_at);
--rollback DROP TABLE audit_event;

--changeset memetrade:0004-audit-event-append-only splitStatements:false
CREATE FUNCTION audit_event_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_reject_change();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_reject_change();
--rollback DROP TRIGGER audit_event_no_truncate ON audit_event; DROP TRIGGER audit_event_no_update_delete ON audit_event; DROP FUNCTION audit_event_reject_change();
