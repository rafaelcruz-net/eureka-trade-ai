--liquibase formatted sql

-- U4 u4-data-requests of 261109-termos-lgpd (infrastructure-specification.md, reliability-design.md NFR5.1): expand
-- only. Three new tables of the Privacy module (the data subject requests, their signature verification and the state
-- of the Privacy purge) with their indexes. No existing row is rewritten and no existing object is changed or removed,
-- so the previous image keeps working on this schema (it ignores the new tables). Every changeset declares its
-- rollback; the rollback of the request tables is only for a local environment before the first real request, because
-- it would delete requests (cicd-pipeline.md). DataRequestsMigrationTest reads this file.
--
-- Grants: the tables are created by the schema OWNER, so the default privileges of db-bootstrap
-- (infra/postgres/bootstrap/10-roles.sql) give SELECT, INSERT, UPDATE and DELETE to the application login, exactly as
-- for the Identity tables (identity_job_run of 0012). They are not append-only: the purge deletes through the main
-- connection, as the purges of u2-retention do. The application login name comes from .env, so no GRANT names it here.

--changeset memetrade:0014-data-subject-request
-- entities.md DataSubjectRequest (BR4.1, BR4.2, BR4.5, BR4.10): a request of a person through the Account (ACCOUNT,
-- tenant and person from the token) or registered by the administrator from the e-mail of the DPO (EMAIL, with the
-- contact; the account is optional and gives the tenant). details, contact, response and refusal_reason never leave
-- the Privacy module through a log, an event or a metric (BR4.3).
CREATE TABLE data_subject_request (
    request_id          uuid          PRIMARY KEY,
    tenant_id           uuid          NULL REFERENCES tenant (tenant_id),
    user_id             uuid          NULL REFERENCES app_user (user_id),
    type                varchar(16)   NOT NULL,
    origin              varchar(8)    NOT NULL,
    details             text          NOT NULL,
    contact             varchar(254)  NULL,
    status              varchar(24)   NOT NULL DEFAULT 'RECEIVED',
    received_at         timestamptz   NOT NULL,
    due_at              timestamptz   NOT NULL,
    response            text          NULL,
    refusal_reason      varchar(500)  NULL,
    closed_at           timestamptz   NULL,
    identity_verified   boolean       NOT NULL DEFAULT false,
    access_summary      jsonb         NULL,
    overdue_notified_at timestamptz   NULL,
    reply_sent_at       timestamptz   NULL,
    CONSTRAINT data_subject_request_type_ck CHECK (type IN ('ACCESS', 'CORRECTION', 'DELETION', 'OTHER')),
    CONSTRAINT data_subject_request_origin_ck CHECK (origin IN ('ACCOUNT', 'EMAIL')),
    CONSTRAINT data_subject_request_status_ck CHECK (
        status IN ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS', 'DONE', 'REFUSED')),
    CONSTRAINT data_subject_request_details_ck CHECK (char_length(details) BETWEEN 1 AND 1000),
    CONSTRAINT data_subject_request_response_ck CHECK (response IS NULL OR char_length(response) <= 4000),
    -- An ACCOUNT request always has its tenant and person and never a contact; an EMAIL request always has a contact,
    -- and a tenant exactly when it is linked to an account.
    CONSTRAINT data_subject_request_origin_fields_ck CHECK (
        (origin = 'ACCOUNT' AND tenant_id IS NOT NULL AND user_id IS NOT NULL AND contact IS NULL)
        OR (origin = 'EMAIL' AND contact IS NOT NULL AND (tenant_id IS NULL) = (user_id IS NULL))),
    CONSTRAINT data_subject_request_due_ck CHECK (due_at > received_at),
    -- Closed exactly in DONE and REFUSED; a refusal always has its reason.
    CONSTRAINT data_subject_request_closed_ck CHECK ((status IN ('DONE', 'REFUSED')) = (closed_at IS NOT NULL)),
    CONSTRAINT data_subject_request_refusal_ck CHECK (
        status <> 'REFUSED' OR (refusal_reason IS NOT NULL AND char_length(btrim(refusal_reason)) >= 1)),
    CONSTRAINT data_subject_request_summary_ck CHECK (access_summary IS NULL OR (type = 'ACCESS' AND status = 'DONE')),
    CONSTRAINT data_subject_request_reply_ck CHECK (
        reply_sent_at IS NULL OR (origin = 'EMAIL' AND closed_at IS NOT NULL))
);
--rollback DROP TABLE data_subject_request;

--changeset memetrade:0014-data-subject-request-indexes
-- NFR3.5: the list of a person (tenant, person, newest first) and the window of the limit per hour.
CREATE INDEX data_subject_request_person_idx ON data_subject_request (tenant_id, user_id, received_at);
-- NFR3.2: the administrator list by status and due date, and the overdue job.
CREATE INDEX data_subject_request_status_due_idx ON data_subject_request (status, due_at);
-- NFR7.1: the purge walks only the closed requests.
CREATE INDEX data_subject_request_closed_idx ON data_subject_request (closed_at) WHERE closed_at IS NOT NULL;
-- BR4.2, NFR3.8: at most one open request per person and type, whatever its origin; a race becomes a unique violation.
CREATE UNIQUE INDEX uq_open_request_per_type ON data_subject_request (tenant_id, user_id, type)
    WHERE status IN ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS') AND user_id IS NOT NULL;
--rollback DROP INDEX uq_open_request_per_type; DROP INDEX data_subject_request_closed_idx; DROP INDEX data_subject_request_status_due_idx; DROP INDEX data_subject_request_person_idx;

--changeset memetrade:0014-data-subject-verification
-- entities.md IdentityVerification (BR4.11 to BR4.13): one verification per EMAIL request linked to an account. The
-- message is not a secret (the administrator sends it to the contact); the signature is never stored.
CREATE TABLE data_subject_verification (
    request_id   uuid        PRIMARY KEY REFERENCES data_subject_request (request_id),
    message      text        NOT NULL,
    issued_at    timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    state        varchar(16) NOT NULL DEFAULT 'PENDING',
    confirmed_at timestamptz NULL,
    CONSTRAINT data_subject_verification_state_ck CHECK (state IN ('PENDING', 'CONFIRMED', 'EXPIRED')),
    CONSTRAINT data_subject_verification_window_ck CHECK (expires_at > issued_at),
    CONSTRAINT data_subject_verification_confirmed_ck CHECK ((state = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);
-- NFR3.4: the job of 15 minutes finds the expired PENDING verifications only.
CREATE INDEX data_subject_verification_pending_idx ON data_subject_verification (expires_at) WHERE state = 'PENDING';
--rollback DROP TABLE data_subject_verification;

--changeset memetrade:0014-privacy-job-run
-- NFR7.1: one row per Privacy purge with the last run, its result, the last success, the cutoff used and the rows
-- removed by that run; read by the health indicator of every instance (same shape as identity_job_run of 0012).
CREATE TABLE privacy_job_run (
    purge_id        varchar(64) PRIMARY KEY,
    last_run_at     timestamptz NOT NULL,
    last_result     varchar(16) NOT NULL,
    last_success_at timestamptz NULL,
    last_cutoff     timestamptz NOT NULL,
    deleted         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT privacy_job_run_purge_ck CHECK (purge_id IN ('dataSubjectRequestPurge')),
    CONSTRAINT privacy_job_run_result_ck CHECK (last_result IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT privacy_job_run_deleted_ck CHECK (deleted >= 0)
);
--rollback DROP TABLE privacy_job_run;
