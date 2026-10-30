--liquibase formatted sql

--changeset memetrade:0002-idempotency-record
-- Common idempotency mechanism (contract-summary.md). (scope, key) is unique; the stored body never holds tokens.
CREATE TABLE idempotency_record (
    scope           varchar(200) NOT NULL,
    idempotency_key uuid         NOT NULL,
    request_digest  char(64)     NOT NULL,
    state           varchar(16)  NOT NULL,
    response_status integer      NULL,
    response_body   jsonb        NULL,
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    CONSTRAINT idempotency_record_pk PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT idempotency_record_state_ck CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_record_completed_ck CHECK ((state = 'COMPLETED') = (response_status IS NOT NULL))
);
CREATE INDEX idempotency_record_expires_idx ON idempotency_record (expires_at);
--rollback DROP TABLE idempotency_record;
