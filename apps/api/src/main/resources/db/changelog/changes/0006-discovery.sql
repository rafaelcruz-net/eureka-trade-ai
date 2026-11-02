--liquibase formatted sql

-- U4 Discovery and assessment (expand only: new tables, new columns, new indexes and the PENDING value; no U1/U2
-- column is removed, so the U2 image keeps working on this schema). Market values are exact numeric, percentages are
-- numeric(7,4) percentage points, and absence is UNKNOWN with a reason, never zero (project.md).

--changeset memetrade:0006-data-source-state
-- BR1.5: a source is PENDING until its first collection; the state is stored and survives restarts. Only rows that
-- never collected (no last_updated_at) move to PENDING; the others keep their state.
ALTER TABLE data_source DROP CONSTRAINT data_source_status_ck;
ALTER TABLE data_source
    ADD CONSTRAINT data_source_status_ck CHECK (status IN ('PENDING', 'OK', 'DEGRADED', 'FAILED'));
-- Category of the last failure, never the error text (NFR9.6).
ALTER TABLE data_source ADD COLUMN failure_category varchar(16) NULL;
ALTER TABLE data_source ADD CONSTRAINT data_source_failure_category_ck
    CHECK (failure_category IS NULL OR failure_category IN ('TIMEOUT', 'ERROR_RESPONSE', 'INVALID_PAYLOAD'));
ALTER TABLE data_source ADD COLUMN collection_interval_seconds integer NOT NULL DEFAULT 30;
ALTER TABLE data_source ADD CONSTRAINT data_source_interval_ck CHECK (collection_interval_seconds BETWEEN 5 AND 60);
-- Sources that are not configured any more (the single "simulated" source of U1) stay for their history but no
-- longer count for the list, the health or the assessment; the source registry keeps this flag in step with
-- MARKET_SOURCES at every start.
ALTER TABLE data_source ADD COLUMN enabled boolean NOT NULL DEFAULT true;
UPDATE data_source SET status = 'PENDING' WHERE last_updated_at IS NULL;
--rollback UPDATE data_source SET status = 'FAILED' WHERE status = 'PENDING';
--rollback ALTER TABLE data_source DROP COLUMN enabled;
--rollback ALTER TABLE data_source DROP COLUMN collection_interval_seconds;
--rollback ALTER TABLE data_source DROP COLUMN failure_category;
--rollback ALTER TABLE data_source DROP CONSTRAINT data_source_status_ck;
--rollback ALTER TABLE data_source ADD CONSTRAINT data_source_status_ck CHECK (status IN ('OK', 'DEGRADED', 'FAILED'));

--changeset memetrade:0006-market-observation-indexes
-- performance-design.md: choice of the displayed value per field (stage 2) and tokens observed in the last 24 h.
CREATE INDEX market_observation_pick_idx ON market_observation (chain, mint, field, observed_at DESC, source_id);
CREATE INDEX market_observation_recent_idx ON market_observation (chain, mint, observed_at);
CREATE INDEX token_first_seen_idx ON token (first_seen_at);
--rollback DROP INDEX token_first_seen_idx;
--rollback DROP INDEX market_observation_recent_idx;
--rollback DROP INDEX market_observation_pick_idx;

--changeset memetrade:0006-observation-sample
-- 24-hour history with a 5-minute resolution (NFR2.4, ADR-008): the first observation of each 5-minute UTC bucket is
-- kept per token, source and field; CREATED_AT never changes and is not kept here. Deleted hourly after 24 hours.
CREATE TABLE observation_sample (
    sample_id      uuid        PRIMARY KEY,
    chain          varchar(64) NOT NULL,
    mint           varchar(44) NOT NULL,
    source_id      varchar(64) NOT NULL REFERENCES data_source (source_id),
    field          varchar(16) NOT NULL,
    status         varchar(8)  NOT NULL,
    value          numeric     NULL,
    unknown_reason varchar(32) NULL,
    observed_at    timestamptz NOT NULL,
    bucket_start   timestamptz NOT NULL,
    CONSTRAINT observation_sample_token_fk FOREIGN KEY (chain, mint) REFERENCES token (chain, mint),
    CONSTRAINT observation_sample_bucket_uq UNIQUE (chain, mint, source_id, field, bucket_start),
    CONSTRAINT observation_sample_field_ck CHECK (field IN ('PRICE', 'LIQUIDITY', 'VOLUME_24H', 'HOLDERS')),
    CONSTRAINT observation_sample_status_ck CHECK (status IN ('KNOWN', 'UNKNOWN')),
    CONSTRAINT observation_sample_reason_ck
        CHECK (unknown_reason IS NULL OR unknown_reason IN ('NOT_PROVIDED', 'SOURCE_UNAVAILABLE', 'NOT_YET_OBSERVED')),
    CONSTRAINT observation_sample_known_xor_unknown_ck CHECK (
        (status = 'KNOWN' AND value IS NOT NULL AND unknown_reason IS NULL)
        OR (status = 'UNKNOWN' AND value IS NULL AND unknown_reason IS NOT NULL)),
    -- NFR6.5: market decimals are never negative and carry at most 18 decimal places.
    CONSTRAINT observation_sample_value_ck CHECK (value IS NULL OR (value >= 0 AND scale(value) <= 18)),
    CONSTRAINT observation_sample_bucket_ck
        CHECK (bucket_start <= observed_at AND observed_at < bucket_start + interval '5 minutes')
) WITH (autovacuum_vacuum_scale_factor = 0.02);
CREATE INDEX observation_sample_lookup_idx ON observation_sample (chain, mint, field, observed_at);
CREATE INDEX observation_sample_observed_idx ON observation_sample (observed_at);
--rollback DROP TABLE observation_sample;

--changeset memetrade:0006-source-status-mark
-- C18 SourceStatusMarks: a single global mark that only grows, changed in the transaction that changes a state.
CREATE TABLE source_status_mark (
    singleton boolean PRIMARY KEY DEFAULT true,
    mark      bigint  NOT NULL DEFAULT 0,
    CONSTRAINT source_status_mark_singleton_ck CHECK (singleton),
    CONSTRAINT source_status_mark_positive_ck CHECK (mark >= 0)
);
INSERT INTO source_status_mark (singleton, mark) VALUES (true, 0);
--rollback DROP TABLE source_status_mark;

--changeset memetrade:0006-risk-weight-set
-- Versions of the indicator weights (F7). Factors and curves as JSONB with decimals as TEXT (never floating point).
-- At most one ACTIVE version at a time; ACTIVE and RETIRED versions never change content (application rule).
CREATE TABLE risk_weight_set (
    version      integer      PRIMARY KEY,
    status       varchar(8)   NOT NULL,
    factors      jsonb        NOT NULL,
    low_max      smallint     NOT NULL,
    medium_max   smallint     NOT NULL,
    min_coverage numeric(7,4) NOT NULL,
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    activated_at timestamptz  NULL,
    retired_at   timestamptz  NULL,
    CONSTRAINT risk_weight_set_version_ck CHECK (version >= 1),
    CONSTRAINT risk_weight_set_status_ck CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT risk_weight_set_bands_ck CHECK (low_max >= 0 AND low_max < medium_max AND medium_max < 100),
    CONSTRAINT risk_weight_set_coverage_ck CHECK (min_coverage > 0 AND min_coverage <= 100),
    CONSTRAINT risk_weight_set_activation_ck CHECK (status = 'DRAFT' OR activated_at IS NOT NULL),
    CONSTRAINT risk_weight_set_retirement_ck CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL)),
    CONSTRAINT risk_weight_set_factors_ck CHECK (jsonb_typeof(factors) = 'array')
);
CREATE UNIQUE INDEX risk_weight_set_one_active_uq ON risk_weight_set ((true)) WHERE status = 'ACTIVE';
-- BR4.3: version 1, ACTIVE, created by "system", no activation event (hypothesis values of the PRD, to calibrate).
INSERT INTO risk_weight_set (version, status, factors, low_max, medium_max, min_coverage, created_at, created_by,
                             activated_at)
VALUES (1, 'ACTIVE', '[
  {"factorId": "LIQUIDITY", "weight": "35.0000", "curve": [
    {"input": "1000", "risk": 100}, {"input": "10000", "risk": 60},
    {"input": "50000", "risk": 25}, {"input": "100000", "risk": 0}]},
  {"factorId": "HOLDERS", "weight": "25.0000", "curve": [
    {"input": "50", "risk": 100}, {"input": "200", "risk": 60},
    {"input": "1000", "risk": 25}, {"input": "5000", "risk": 0}]},
  {"factorId": "VOLUME_24H", "weight": "20.0000", "curve": [
    {"input": "1000", "risk": 100}, {"input": "10000", "risk": 60},
    {"input": "100000", "risk": 25}, {"input": "1000000", "risk": 0}]},
  {"factorId": "PRICE_CHANGE_1H", "weight": "20.0000", "curve": [
    {"input": "0", "risk": 0}, {"input": "10", "risk": 30},
    {"input": "30", "risk": 70}, {"input": "60", "risk": 100}]}
]'::jsonb, 39, 69, 80.0000, now(), 'system', now());
--rollback DROP TABLE risk_weight_set;

--changeset memetrade:0006-risk-assessment
-- The latest assessment per token (Q8): replaced on every recalculation; never overwritten by an older weight version
-- (application guard in the ON CONFLICT clause). Factors keep the inputs, sources and observation times (NFR8.1).
CREATE TABLE risk_assessment (
    chain                  varchar(64)  NOT NULL,
    mint                   varchar(44)  NOT NULL,
    assessment_id          uuid         NOT NULL,
    weight_set_version     integer      NOT NULL REFERENCES risk_weight_set (version),
    status                 varchar(12)  NOT NULL,
    score                  smallint     NULL,
    band                   varchar(8)   NULL,
    band_raised_by_unknown boolean      NOT NULL DEFAULT false,
    unavailable_reason     varchar(32)  NULL,
    coverage               numeric(7,4) NOT NULL,
    decision_hint          varchar(10)  NOT NULL,
    factors                jsonb        NOT NULL,
    inputs_fingerprint     varchar(64)  NOT NULL,
    computed_at            timestamptz  NOT NULL,
    CONSTRAINT risk_assessment_pk PRIMARY KEY (chain, mint),
    CONSTRAINT risk_assessment_id_uq UNIQUE (assessment_id),
    CONSTRAINT risk_assessment_token_fk FOREIGN KEY (chain, mint) REFERENCES token (chain, mint),
    CONSTRAINT risk_assessment_status_ck CHECK (status IN ('AVAILABLE', 'UNAVAILABLE')),
    CONSTRAINT risk_assessment_band_ck CHECK (band IS NULL OR band IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT risk_assessment_reason_ck
        CHECK (unavailable_reason IS NULL OR unavailable_reason IN ('INSUFFICIENT_COVERAGE', 'SOURCE_UNAVAILABLE')),
    CONSTRAINT risk_assessment_hint_ck CHECK (decision_hint IN ('NO_TRADE', 'ELIGIBLE')),
    CONSTRAINT risk_assessment_score_ck CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT risk_assessment_coverage_ck CHECK (coverage >= 0 AND coverage <= 100),
    -- BR3.3, BR3.4, BR3.8: AVAILABLE has a score and a band; UNAVAILABLE has a reason, no score, and is NO_TRADE.
    CONSTRAINT risk_assessment_consistency_ck CHECK (
        (status = 'AVAILABLE' AND score IS NOT NULL AND band IS NOT NULL AND unavailable_reason IS NULL
            AND decision_hint = 'ELIGIBLE')
        OR (status = 'UNAVAILABLE' AND score IS NULL AND band IS NULL AND unavailable_reason IS NOT NULL
            AND decision_hint = 'NO_TRADE' AND NOT band_raised_by_unknown)),
    CONSTRAINT risk_assessment_raised_ck CHECK (NOT band_raised_by_unknown OR band = 'MEDIUM'),
    CONSTRAINT risk_assessment_factors_ck CHECK (jsonb_typeof(factors) = 'array')
);
--rollback DROP TABLE risk_assessment;
