--liquibase formatted sql

-- 261113-trading-real-bot, U4 u4-real-execution (correção do ponta a ponta, CI do commit 605a8fb): the cycle of a REAL
-- bot records its outcome as REAL_<result of M5/M6> and skips with REAL_UNAVAILABLE, but bot_cycle (migration 0010)
-- only knew the PAPER outcomes in a varchar(12): every REAL cycle rolled back with a DataIntegrityViolationException.
-- Expand only: the outcome column grows (a varchar widening rewrites nothing in PostgreSQL) and both checks accept the
-- REAL values next to the PAPER ones, so the image of the U3 keeps working on this schema.

--changeset memetrade:0020-bot-cycle-real-outcomes
ALTER TABLE bot_cycle ALTER COLUMN outcome TYPE varchar(32);
ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck;
ALTER TABLE bot_cycle ADD CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL
    OR outcome IN ('NO_ORDER', 'FILLED', 'NOT_FILLED', 'REJECTED', 'DUPLICATE', 'CONFLICT',
                   'REAL_RECORDED', 'REAL_ORDER_IN_PROGRESS', 'REAL_LIQUIDATION_ACTIVE', 'REAL_BOT_NOT_OWNED',
                   'REAL_REJECTED', 'REAL_UNAVAILABLE'));
ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_skip_ck;
ALTER TABLE bot_cycle ADD CONSTRAINT bot_cycle_skip_ck CHECK (skip_reason IS NULL
    OR skip_reason IN ('NOT_ACTIVE', 'PAUSED', 'SLOT_EXPIRED', 'REAL_UNAVAILABLE'));
--rollback ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_skip_ck;
--rollback ALTER TABLE bot_cycle ADD CONSTRAINT bot_cycle_skip_ck CHECK (skip_reason IS NULL OR skip_reason IN ('NOT_ACTIVE', 'PAUSED', 'SLOT_EXPIRED'));
--rollback ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck;
--rollback ALTER TABLE bot_cycle ADD CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL OR outcome IN ('NO_ORDER', 'FILLED', 'NOT_FILLED', 'REJECTED', 'DUPLICATE', 'CONFLICT'));
--rollback ALTER TABLE bot_cycle ALTER COLUMN outcome TYPE varchar(12);
