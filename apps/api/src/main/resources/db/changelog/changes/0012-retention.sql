--liquibase formatted sql

-- U2 u2-retention of 261109-termos-lgpd (infrastructure-specification.md, reliability-design.md NFR5.1, BR2.14): expand
-- only. One new column with its partial index on the trail, one new state table of the Identity purges and one
-- expression index for the session purge. No existing row is rewritten and no existing object is removed, so the
-- previous image keeps working on this schema (it ignores the new column, table and indexes). Every changeset declares
-- its rollback. RetentionMigrationTest reads this file and fails when a statement outside the rollback lines rewrites
-- or removes data or objects.

--changeset memetrade:0012-audit-event-wallet-hmac
-- BR2.1, BR2.18: the HMAC-SHA-256 of the wallet address (lower-case hex, 64 characters), copied by the relay from the
-- outbox payload; empty for older events and for events without a wallet. The full address is never stored here.
ALTER TABLE audit_event ADD COLUMN wallet_hmac varchar(64) NULL
    CONSTRAINT audit_event_wallet_hmac_ck CHECK (wallet_hmac ~ '^[0-9a-f]{64}$');
--rollback ALTER TABLE audit_event DROP COLUMN wallet_hmac;

--changeset memetrade:0012-audit-event-wallet-hmac-idx
-- NFR3.8: partial index, only the events that carry a wallet (U4 and U5 look events up by the HMAC of a person).
CREATE INDEX audit_event_wallet_hmac_idx ON audit_event (wallet_hmac) WHERE wallet_hmac IS NOT NULL;
--rollback DROP INDEX audit_event_wallet_hmac_idx;

--changeset memetrade:0012-identity-job-run
-- BR2.16, NFR7.2: one row per new Identity purge with the last run, its result, the last success, the cutoff used and
-- the rows removed by that run; read by the health indicators of every instance.
CREATE TABLE identity_job_run (
    purge_id        varchar(64) PRIMARY KEY,
    last_run_at     timestamptz NOT NULL,
    last_result     varchar(16) NOT NULL,
    last_success_at timestamptz NULL,
    last_cutoff     timestamptz NOT NULL,
    deleted         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT identity_job_run_purge_ck CHECK (purge_id IN ('loginChallengePurge', 'sessionPurge')),
    CONSTRAINT identity_job_run_result_ck CHECK (last_result IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT identity_job_run_deleted_ck CHECK (deleted >= 0)
);
--rollback DROP TABLE identity_job_run;

--changeset memetrade:0012-user-session-end-idx
-- NFR3.5, BR2.6: the end of a session is revoked_at when it was revoked, otherwise the earlier of its two expirations.
-- SessionPurgeJob filters and orders by exactly this expression.
CREATE INDEX user_session_end_idx ON user_session ((coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))));
--rollback DROP INDEX user_session_end_idx;
