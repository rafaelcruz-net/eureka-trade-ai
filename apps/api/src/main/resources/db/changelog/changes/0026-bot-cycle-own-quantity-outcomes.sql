--liquibase formatted sql

-- 261121-paper-real-unico, U4 u4-own-quantity-cycle (NFR2.7, NFR3.8; reliability-design.md "Migração 0026"): the REAL
-- cycle records two new outcomes, REAL_NO_OWN_QUANTITY (the bot has no own quantity available to sell) and
-- REAL_OWN_QUANTITY_UNAVAILABLE (its own holdings could not be read, or its mark changed under the lock). Expand only:
-- the rule of bot_cycle.outcome accepts the 12 values of today plus the two new ones, so the previous image keeps
-- writing on this schema. bot_cycle grows every cycle, so the rule is replaced in two changesets:
--   1. one ALTER TABLE with the DROP and the ADD ... NOT VALID: ACCESS EXCLUSIVE only for the swap of the catalog, no
--      scan of the table;
--   2. VALIDATE CONSTRAINT in a changeset of its own: the scan takes only SHARE UPDATE EXCLUSIVE and never stops the
--      inserts of the cycles.
-- Each waits at most 5 s for its lock (SET LOCAL, only for its own transaction): a long transaction on bot_cycle makes
-- the migration fail with 55P03 and the deployment stop before the new images (runbook, section 12), instead of
-- queueing the cycles behind it. No GRANT, REVOKE nor change of data.

--changeset memetrade:0026-bot-cycle-outcome-not-valid
SET LOCAL lock_timeout = '5s';
ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck,
    ADD CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL
        OR outcome IN ('NO_ORDER', 'FILLED', 'NOT_FILLED', 'REJECTED', 'DUPLICATE', 'CONFLICT',
                       'REAL_RECORDED', 'REAL_ORDER_IN_PROGRESS', 'REAL_LIQUIDATION_ACTIVE', 'REAL_BOT_NOT_OWNED',
                       'REAL_REJECTED', 'REAL_UNAVAILABLE', 'REAL_NO_OWN_QUANTITY', 'REAL_OWN_QUANTITY_UNAVAILABLE'))
    NOT VALID;
--rollback SET LOCAL lock_timeout = '5s';
--rollback ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck, ADD CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL OR outcome IN ('NO_ORDER', 'FILLED', 'NOT_FILLED', 'REJECTED', 'DUPLICATE', 'CONFLICT', 'REAL_RECORDED', 'REAL_ORDER_IN_PROGRESS', 'REAL_LIQUIDATION_ACTIVE', 'REAL_BOT_NOT_OWNED', 'REAL_REJECTED', 'REAL_UNAVAILABLE')) NOT VALID;
--rollback ALTER TABLE bot_cycle VALIDATE CONSTRAINT bot_cycle_outcome_ck;

--changeset memetrade:0026-bot-cycle-outcome-validate
-- A validation cannot be undone: the rollback of this changeset does nothing, and the rollback of the previous one
-- brings back the list of today, NOT VALID then validated (safe only while no row carries a new value; with the flag of
-- the U4 off none is ever written). In production no migration is reverted, only the images go back (team.md).
SET LOCAL lock_timeout = '5s';
ALTER TABLE bot_cycle VALIDATE CONSTRAINT bot_cycle_outcome_ck;
--rollback SELECT 1;
