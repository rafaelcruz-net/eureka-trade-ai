--liquibase formatted sql

-- 261121-paper-real-unico, U4 u4-own-quantity-cycle (NFR1.11, NFR1.12; performance-design.md "Índices: decisão"): the
-- open sales of a bot (C16 extended, D1) read its PENDING SELL decisions by this small partial index; the queue index
-- (state, created_at) would read the pending decisions of every person and filter the bot in the heap. Expand only.

--changeset memetrade:0027-real-decision-request-bot-pending-sell-idx runInTransaction:false
-- CONCURRENTLY keeps the cycle and the consumer writing the queue during the deployment; PostgreSQL refuses it inside a
-- transaction block, hence runInTransaction:false, and each statement goes on its own. An interrupted build leaves an
-- INVALID index and no row of this changeset: the DROP below removes it on the next run. Never a create guarded by an
-- existence test, which would silently keep an INVALID index (Migration0027IndexRestartIT).
DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;
CREATE INDEX CONCURRENTLY real_decision_request_bot_pending_sell_idx
    ON real_decision_request (bot_id) WHERE state = 'PENDING' AND side = 'SELL';
--rollback DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;
