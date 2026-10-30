--liquibase formatted sql

-- MarketData tables. Market values are exact numeric (never floating point); absence is UNKNOWN, never zero.

--changeset memetrade:0003-data-source
CREATE TABLE data_source (
    source_id       varchar(64)  PRIMARY KEY,
    name            varchar(120) NOT NULL,
    simulated       boolean      NOT NULL,
    status          varchar(16)  NOT NULL,
    last_updated_at timestamptz  NULL,
    last_attempt_at timestamptz  NULL,
    CONSTRAINT data_source_status_ck CHECK (status IN ('OK', 'DEGRADED', 'FAILED'))
);
--rollback DROP TABLE data_source;

--changeset memetrade:0003-token
-- Identity is (chain, mint) (BR3.2). sort_group/sort_key denormalise the listing order (BR3.6):
-- sort_group 0 = creation time KNOWN, 1 = UNKNOWN (always last); sort_key = -(creation epoch micros) so that
-- ascending order lists the newest first. Maintained only by the MarketData collector.
CREATE TABLE token (
    chain            varchar(64)  NOT NULL,
    mint             varchar(44)  NOT NULL,
    symbol           varchar(32)  NOT NULL,
    name             varchar(120) NULL,
    decimals         smallint     NOT NULL,
    first_seen_at    timestamptz  NOT NULL,
    token_created_at timestamptz  NULL,
    sort_group       smallint     NOT NULL DEFAULT 1,
    sort_key         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT token_pk PRIMARY KEY (chain, mint),
    CONSTRAINT token_decimals_ck CHECK (decimals BETWEEN 0 AND 18),
    CONSTRAINT token_sort_group_ck CHECK (sort_group IN (0, 1)),
    CONSTRAINT token_sort_consistency_ck CHECK ((sort_group = 0) = (token_created_at IS NOT NULL))
);
CREATE INDEX token_listing_order_idx ON token (sort_group, sort_key, mint, chain);
--rollback DROP TABLE token;

--changeset memetrade:0003-market-observation
CREATE TABLE market_observation (
    observation_id uuid        PRIMARY KEY,
    chain          varchar(64) NOT NULL,
    mint           varchar(44) NOT NULL,
    source_id      varchar(64) NOT NULL REFERENCES data_source (source_id),
    field          varchar(16) NOT NULL,
    status         varchar(8)  NOT NULL,
    value          numeric     NULL,
    unknown_reason varchar(32) NULL,
    observed_at    timestamptz NOT NULL,
    CONSTRAINT market_observation_token_fk FOREIGN KEY (chain, mint) REFERENCES token (chain, mint),
    CONSTRAINT market_observation_current_uq UNIQUE (chain, mint, source_id, field),
    CONSTRAINT market_observation_field_ck
        CHECK (field IN ('PRICE', 'LIQUIDITY', 'VOLUME_24H', 'HOLDERS', 'CREATED_AT')),
    CONSTRAINT market_observation_status_ck CHECK (status IN ('KNOWN', 'UNKNOWN')),
    CONSTRAINT market_observation_reason_ck
        CHECK (unknown_reason IS NULL OR unknown_reason IN ('NOT_PROVIDED', 'SOURCE_UNAVAILABLE', 'NOT_YET_OBSERVED')),
    -- Exactly one of value / unknown_reason, according to status (BR3.1).
    CONSTRAINT market_observation_known_xor_unknown_ck CHECK (
        (status = 'KNOWN' AND value IS NOT NULL AND unknown_reason IS NULL)
        OR (status = 'UNKNOWN' AND value IS NULL AND unknown_reason IS NOT NULL))
);
--rollback DROP TABLE market_observation;
