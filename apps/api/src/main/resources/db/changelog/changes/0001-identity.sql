--liquibase formatted sql

-- IdentityAccess tables (entities.md). Instants are timestamptz (UTC); enums are upper case.

--changeset memetrade:0001-tenant
CREATE TABLE tenant (
    tenant_id  uuid         PRIMARY KEY,
    name       varchar(120) NOT NULL,
    status     varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz  NOT NULL,
    CONSTRAINT tenant_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
--rollback DROP TABLE tenant;

--changeset memetrade:0001-app-user
CREATE TABLE app_user (
    user_id    uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (tenant_id),
    role       varchar(16) NOT NULL,
    status     varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    CONSTRAINT app_user_role_ck CHECK (role IN ('BETA_USER', 'ADMIN')),
    CONSTRAINT app_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX app_user_tenant_idx ON app_user (tenant_id);
--rollback DROP TABLE app_user;

--changeset memetrade:0001-wallet
CREATE TABLE wallet (
    wallet_id   uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL UNIQUE REFERENCES app_user (user_id),
    address     varchar(44) NOT NULL UNIQUE,
    provider    varchar(16) NOT NULL,
    verified_at timestamptz NOT NULL,
    CONSTRAINT wallet_address_base58_ck CHECK (address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    CONSTRAINT wallet_provider_ck CHECK (provider IN ('PHANTOM', 'SOLFLARE'))
);
--rollback DROP TABLE wallet;

--changeset memetrade:0001-login-challenge
-- The nonce at rest is not a secret: it is public in the message, single-use and expires in 5 minutes (BR1.10).
CREATE TABLE login_challenge (
    nonce          varchar(64)  PRIMARY KEY,
    wallet_address varchar(44)  NOT NULL,
    domain         varchar(255) NOT NULL,
    chain          varchar(64)  NOT NULL,
    message        text         NOT NULL,
    issued_at      timestamptz  NOT NULL,
    expires_at     timestamptz  NOT NULL,
    state          varchar(16)  NOT NULL DEFAULT 'ISSUED',
    consumed_at    timestamptz  NULL,
    CONSTRAINT login_challenge_state_ck CHECK (state IN ('ISSUED', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT login_challenge_window_ck CHECK (expires_at > issued_at),
    CONSTRAINT login_challenge_consumed_ck CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL))
);
CREATE INDEX login_challenge_expires_idx ON login_challenge (expires_at);
--rollback DROP TABLE login_challenge;

--changeset memetrade:0001-user-session
-- Only the SHA-256 digest of the refresh token is stored; the token itself never is (NFR6.6).
CREATE TABLE user_session (
    session_id           uuid        PRIMARY KEY,
    user_id              uuid        NOT NULL REFERENCES app_user (user_id),
    tenant_id            uuid        NOT NULL REFERENCES tenant (tenant_id),
    role                 varchar(16) NOT NULL,
    refresh_token_digest char(64)    NOT NULL UNIQUE,
    created_at           timestamptz NOT NULL,
    refresh_expires_at   timestamptz NOT NULL,
    absolute_expires_at  timestamptz NOT NULL,
    state                varchar(16) NOT NULL DEFAULT 'ACTIVE',
    revoked_at           timestamptz NULL,
    CONSTRAINT user_session_role_ck CHECK (role IN ('BETA_USER')),
    CONSTRAINT user_session_state_ck CHECK (state IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT user_session_refresh_bound_ck CHECK (refresh_expires_at <= absolute_expires_at)
);
CREATE INDEX user_session_user_idx ON user_session (user_id);
--rollback DROP TABLE user_session;

--changeset memetrade:0001-session-used-refresh-digest
CREATE TABLE session_used_refresh_digest (
    digest     char(64)    PRIMARY KEY,
    session_id uuid        NOT NULL REFERENCES user_session (session_id),
    used_at    timestamptz NOT NULL
);
CREATE INDEX session_used_refresh_digest_session_idx ON session_used_refresh_digest (session_id);
--rollback DROP TABLE session_used_refresh_digest;
