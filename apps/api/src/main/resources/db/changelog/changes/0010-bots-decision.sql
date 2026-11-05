--liquibase formatted sql

-- U5 BotManagement and DecisionEngine (entities.md, logical-components.md, scalability-design.md,
-- infrastructure-specification.md). Expand only: new tables, indexes, a sequence, checks, triggers and seeds; nothing
-- of U1 to U7 changes, so the U6 image keeps working on this schema with the new tables unused (rollback of
-- cicd-pipeline.md). Applied by db-migrate with the schema OWNER; the default privileges of db-bootstrap give the
-- application login SELECT, INSERT, UPDATE and DELETE on the new tables (the decision purge of BR9.1 deletes with it).
-- Scales: USDC numeric(38,6), unit prices numeric(38,12), token quantities numeric(38,18), percentage points
-- numeric(9,4). Every customer row carries tenant, person and mode, and mode is only PAPER (NFR5.3).

--changeset memetrade:0010-strategy-template
-- FR7.1, BR4.3 to BR4.6: the v1 template strategies, seeded here and read by the wizard (C4).
CREATE TABLE strategy_template (
    template_id   varchar(32)   PRIMARY KEY,
    version       varchar(16)   NOT NULL,
    name          varchar(100)  NOT NULL,
    description   varchar(400)  NOT NULL,
    parameters    jsonb         NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT strategy_template_id_ck CHECK (template_id IN ('MOMENTUM_VOLUME', 'RANGE_BREAKOUT'))
);
INSERT INTO strategy_template (template_id, version, name, description, parameters) VALUES
('MOMENTUM_VOLUME', 'v1', 'Momentum de volume',
 'Compra quando o volume de 24 h e o preço sobem pelo menos o mínimo dentro da janela. Com o padrão de 20%, o volume da janela precisa ser perto de um quinto do dia, então o bot compra raramente.',
 '[{"key":"windowMinutes","label":"Janela","unit":"min","default":"15","min":"10","max":"60","step":"5"},
   {"key":"volumeGrowthMinPct","label":"Crescimento mínimo do volume de 24 h","unit":"%","default":"20.0000","min":"5.0000","max":"200.0000","step":"1.0000"},
   {"key":"priceRiseMinPct","label":"Alta mínima do preço","unit":"%","default":"2.0000","min":"0.5000","max":"20.0000","step":"0.5000"},
   {"key":"takeProfitPct","label":"Alvo de lucro (take profit)","unit":"%","default":"10.0000","min":"1.0000","max":"100.0000","step":"0.5000"},
   {"key":"stopLossPct","label":"Limite de perda (stop loss)","unit":"%","default":"5.0000","min":"1.0000","max":"50.0000","step":"0.5000"},
   {"key":"maxHoldMinutes","label":"Tempo máximo da posição","unit":"min","default":"240","min":"15","max":"1440","step":"15"},
   {"key":"buyFractionPct","label":"Fração do saldo por compra","unit":"%","default":"10.0000","min":"1.0000","max":"100.0000","step":"1.0000"}]'::jsonb),
('RANGE_BREAKOUT', 'v1', 'Rompimento de faixa',
 'Compra quando o último preço passa da máxima da faixa da janela mais a margem; vende ao voltar abaixo da mínima da faixa.',
 '[{"key":"windowMinutes","label":"Janela da faixa","unit":"min","default":"120","min":"30","max":"720","step":"30"},
   {"key":"breakoutMarginPct","label":"Margem do rompimento","unit":"%","default":"1.0000","min":"0.1000","max":"10.0000","step":"0.1000"},
   {"key":"takeProfitPct","label":"Alvo de lucro (take profit)","unit":"%","default":"10.0000","min":"1.0000","max":"100.0000","step":"0.5000"},
   {"key":"stopLossPct","label":"Limite de perda (stop loss)","unit":"%","default":"5.0000","min":"1.0000","max":"50.0000","step":"0.5000"},
   {"key":"maxHoldMinutes","label":"Tempo máximo da posição","unit":"min","default":"240","min":"15","max":"1440","step":"15"},
   {"key":"buyFractionPct","label":"Fração do saldo por compra","unit":"%","default":"10.0000","min":"1.0000","max":"100.0000","step":"1.0000"}]'::jsonb);
--rollback DROP TABLE strategy_template;

--changeset memetrade:0010-bot-owner
-- BR2.11: the control row of a person, locked before the bot by every operation that counts active bots. It keeps no
-- counter; the count is made under the lock. Created by the first POST /bots of the person (insert on conflict nothing).
CREATE TABLE bot_owner (
    tenant_id     uuid         NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id uuid         NOT NULL REFERENCES app_user (user_id),
    created_at    timestamptz  NOT NULL,
    CONSTRAINT bot_owner_pk PRIMARY KEY (tenant_id, owner_user_id)
);
--rollback DROP TABLE bot_owner;

--changeset memetrade:0010-bot
-- BR8.1: the global sequence of the change marks (C18); bots are never deleted, so the mark only grows.
CREATE SEQUENCE bot_change_seq;
-- FR7.5: a PAPER bot of one person, from the draft to the end. pause_reasons is the comma separated set of
-- PauseReason in declaration order; state = PAUSED if and only if it is not empty.
CREATE TABLE bot (
    bot_id                  uuid           PRIMARY KEY,
    tenant_id               uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id           uuid           NOT NULL REFERENCES app_user (user_id),
    mode                    varchar(8)     NOT NULL DEFAULT 'PAPER',
    name                    varchar(60)    NOT NULL,
    template_id             varchar(32)    NOT NULL REFERENCES strategy_template (template_id),
    template_version        varchar(16)    NOT NULL,
    parameters              jsonb          NULL,
    token_selection         jsonb          NULL,
    virtual_balance         numeric(38,6)  NULL,
    state                   varchar(16)    NOT NULL,
    pause_reasons           varchar(120)   NOT NULL DEFAULT '',
    change_seq              bigint         NOT NULL DEFAULT nextval('bot_change_seq'),
    draft_step              smallint       NOT NULL DEFAULT 1,
    current_limits_version  integer        NULL,
    draft_limits            jsonb          NULL,
    funded_at               timestamptz    NULL,
    activated_at            timestamptz    NULL,
    paused_at               timestamptz    NULL,
    stopped_at              timestamptz    NULL,
    created_at              timestamptz    NOT NULL,
    updated_at              timestamptz    NOT NULL,
    version                 bigint         NOT NULL DEFAULT 0,
    CONSTRAINT bot_owner_fk FOREIGN KEY (tenant_id, owner_user_id) REFERENCES bot_owner (tenant_id, owner_user_id),
    CONSTRAINT bot_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT bot_state_ck CHECK (state IN ('DRAFT', 'VALIDATED', 'ACTIVE', 'PAUSED', 'CLOSING_PENDING', 'STOPPED')),
    CONSTRAINT bot_paused_ck CHECK ((state = 'PAUSED') = (pause_reasons <> '')),
    CONSTRAINT bot_draft_step_ck CHECK (draft_step BETWEEN 1 AND 4),
    CONSTRAINT bot_balance_ck CHECK (virtual_balance IS NULL OR virtual_balance > 0),
    CONSTRAINT bot_name_ck CHECK (length(trim(name)) > 0)
);
-- scalability-design.md: the list and the count of active bots of a person.
CREATE INDEX bot_owner_state_idx ON bot (tenant_id, owner_user_id, state, updated_at DESC);
-- The slot dispatch: only the ACTIVE bots.
CREATE INDEX bot_active_idx ON bot (state) WHERE state = 'ACTIVE';
-- The change mark of a person (C18), read by the live channel without locking any row.
CREATE INDEX bot_change_mark_idx ON bot (tenant_id, owner_user_id, change_seq DESC);
--rollback DROP TABLE bot; DROP SEQUENCE bot_change_seq;

--changeset memetrade:0010-bot-limits-version
-- FR7.3, FR7.8, BR5.1: one immutable version of the limits; the values go to U6 on every order (C14).
CREATE TABLE bot_limits_version (
    bot_id                uuid           NOT NULL REFERENCES bot (bot_id),
    version               integer        NOT NULL,
    max_order_size        numeric(38,6)  NOT NULL,
    max_token_exposure    numeric(38,6)  NOT NULL,
    max_total_exposure    numeric(38,6)  NOT NULL,
    max_daily_loss        numeric(38,6)  NOT NULL,
    max_drawdown_pct      numeric(9,4)   NOT NULL,
    max_slippage_pct      numeric(9,4)   NOT NULL,
    min_liquidity         numeric(38,6)  NOT NULL,
    min_interval_seconds  integer        NOT NULL,
    created_at            timestamptz    NOT NULL,
    created_by            uuid           NOT NULL REFERENCES app_user (user_id),
    CONSTRAINT bot_limits_version_pk PRIMARY KEY (bot_id, version),
    CONSTRAINT bot_limits_version_ck CHECK (version >= 1),
    CONSTRAINT bot_limits_positive_ck CHECK (max_order_size > 0 AND max_token_exposure > 0 AND max_total_exposure > 0
        AND max_daily_loss > 0 AND min_liquidity >= 0 AND min_interval_seconds >= 1),
    CONSTRAINT bot_limits_pct_ck CHECK (max_drawdown_pct > 0 AND max_drawdown_pct <= 100
        AND max_slippage_pct > 0 AND max_slippage_pct <= 100),
    CONSTRAINT bot_limits_coherent_ck CHECK (max_order_size <= max_token_exposure
        AND max_token_exposure <= max_total_exposure)
);
--rollback DROP TABLE bot_limits_version;

--changeset memetrade:0010-bot-cycle
-- BR3.2, NFR4.7: one cycle per bot and 5-minute slot, never twice; RUNNING exists only inside the cycle transaction.
CREATE TABLE bot_cycle (
    cycle_id        uuid           PRIMARY KEY,
    bot_id          uuid           NOT NULL REFERENCES bot (bot_id),
    tenant_id       uuid           NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id   uuid           NOT NULL REFERENCES app_user (user_id),
    slot_start      timestamptz    NOT NULL,
    status          varchar(12)    NOT NULL,
    outcome         varchar(12)    NULL,
    skip_reason     varchar(16)    NULL,
    failure_cause   varchar(200)   NULL,
    decision_id     uuid           NULL,
    started_at      timestamptz    NOT NULL,
    finished_at     timestamptz    NULL,
    CONSTRAINT bot_cycle_slot_uq UNIQUE (bot_id, slot_start),
    CONSTRAINT bot_cycle_status_ck CHECK (status IN ('RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED')),
    CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL
        OR outcome IN ('NO_ORDER', 'FILLED', 'NOT_FILLED', 'REJECTED', 'DUPLICATE', 'CONFLICT')),
    CONSTRAINT bot_cycle_skip_ck CHECK (skip_reason IS NULL OR skip_reason IN ('NOT_ACTIVE', 'PAUSED', 'SLOT_EXPIRED'))
);
-- The health indicator reads the last finished cycle; the purge selects old slots.
CREATE INDEX bot_cycle_finished_idx ON bot_cycle (finished_at DESC);
CREATE INDEX bot_cycle_slot_idx ON bot_cycle (slot_start);
--rollback DROP TABLE bot_cycle;

--changeset memetrade:0010-bot-holding
-- BR3.7, Q3: when U5 opened the position of a token, for the exit by maximum time.
CREATE TABLE bot_holding (
    bot_id      uuid          NOT NULL REFERENCES bot (bot_id),
    chain       varchar(64)   NOT NULL,
    token_mint  varchar(64)   NOT NULL,
    opened_at   timestamptz   NOT NULL,
    CONSTRAINT bot_holding_pk PRIMARY KEY (bot_id, chain, token_mint)
);
--rollback DROP TABLE bot_holding;

--changeset memetrade:0010-system-pause-state
-- C16, BR7.1: the copy of U5 of the kill switch; ONE row, written by applySystemPause and liftSystemPause with the row
-- locked, read with a shared lock by start and resume (BR2.11).
CREATE TABLE system_pause_state (
    id              smallint      PRIMARY KEY DEFAULT 1,
    active          boolean       NOT NULL DEFAULT false,
    changed_at      timestamptz   NOT NULL,
    correlation_id  varchar(100)  NOT NULL,
    CONSTRAINT system_pause_state_single_ck CHECK (id = 1)
);
INSERT INTO system_pause_state (id, active, changed_at, correlation_id) VALUES (1, false, now(), 'migration-0010');
--rollback DROP TABLE system_pause_state;

--changeset memetrade:0010-decision
-- FR8.1, AC4.5.1, BR4.10: the explained and reproducible decision of a cycle, owned by the DecisionEngine. factors,
-- inputs and context are JSONB with exact decimals as TEXT, read only on the page of the list (performance-design.md).
CREATE TABLE decision (
    decision_id           uuid            PRIMARY KEY,
    bot_id                uuid            NOT NULL REFERENCES bot (bot_id),
    cycle_id              uuid            NOT NULL REFERENCES bot_cycle (cycle_id),
    tenant_id             uuid            NOT NULL REFERENCES tenant (tenant_id),
    owner_user_id         uuid            NOT NULL REFERENCES app_user (user_id),
    mode                  varchar(8)      NOT NULL DEFAULT 'PAPER',
    chain                 varchar(64)     NULL,
    token_mint            varchar(64)     NULL,
    action                varchar(8)      NOT NULL,
    no_trade_reason       varchar(32)     NULL,
    exit_reason           varchar(16)     NULL,
    requested_quantity    numeric(38,18)  NULL,
    strategy_template_id  varchar(32)     NOT NULL,
    strategy_version      varchar(16)     NOT NULL,
    parameters            jsonb           NOT NULL,
    limits_version        integer         NOT NULL,
    factors               jsonb           NOT NULL,
    inputs                jsonb           NOT NULL,
    context               jsonb           NOT NULL,
    summary               varchar(300)    NOT NULL,
    valid_until           timestamptz     NOT NULL,
    created_at            timestamptz     NOT NULL,
    CONSTRAINT decision_cycle_uq UNIQUE (cycle_id),
    CONSTRAINT decision_mode_ck CHECK (mode = 'PAPER'),
    CONSTRAINT decision_action_ck CHECK (action IN ('BUY', 'SELL', 'HOLD', 'NO_TRADE')),
    CONSTRAINT decision_reason_ck CHECK (action <> 'NO_TRADE' OR no_trade_reason IS NOT NULL),
    CONSTRAINT decision_exit_ck CHECK (action <> 'SELL' OR exit_reason IS NOT NULL),
    CONSTRAINT decision_order_ck CHECK (action NOT IN ('BUY', 'SELL')
        OR (token_mint IS NOT NULL AND requested_quantity > 0))
);
-- The list of the decisions of a bot by cursor (NFR2.5) and the purge of HOLD and NO_TRADE (BR9.1).
CREATE INDEX decision_bot_idx ON decision (bot_id, created_at DESC, decision_id);
CREATE INDEX decision_purge_idx ON decision (created_at) WHERE action IN ('HOLD', 'NO_TRADE');
--rollback DROP TABLE decision;

--changeset memetrade:0010-triggers splitStatements:false
-- A limits version and a decision are immutable once recorded (entities.md); the purge of BR9.1 may still DELETE the
-- HOLD and NO_TRADE decisions, never update them.
CREATE OR REPLACE FUNCTION bots_reject_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is immutable: % is not allowed', TG_TABLE_NAME, TG_OP USING ERRCODE = 'check_violation';
END;
$$;
CREATE TRIGGER bot_limits_version_immutable BEFORE UPDATE OR DELETE ON bot_limits_version
    FOR EACH ROW EXECUTE FUNCTION bots_reject_update();
CREATE TRIGGER decision_immutable BEFORE UPDATE ON decision
    FOR EACH ROW EXECUTE FUNCTION bots_reject_update();
--rollback DROP TRIGGER decision_immutable ON decision; DROP TRIGGER bot_limits_version_immutable ON bot_limits_version; DROP FUNCTION bots_reject_update();
