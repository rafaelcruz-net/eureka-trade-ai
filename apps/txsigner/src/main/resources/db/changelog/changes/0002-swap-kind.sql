--liquibase formatted sql

--changeset txsigner:0002-signing-record-swap-kind
-- 261113-trading-real-bot U4 (review round): the signing service records a swap with the operation kind SWAP of the
-- contract R1 (the side travels in the context and in the audit event). The U1 constraint expected SWAP_BUY or
-- SWAP_SELL, so every swap decision failed on insert (500, found by RealOffByDefaultIT). SWAP_BUY and SWAP_SELL stay
-- allowed so no existing row becomes invalid.
ALTER TABLE signing_record DROP CONSTRAINT signing_record_kind_ck;
ALTER TABLE signing_record ADD CONSTRAINT signing_record_kind_ck
    CHECK (operation_kind IN ('WITHDRAWAL', 'SWAP', 'SWAP_BUY', 'SWAP_SELL'));
--rollback ALTER TABLE signing_record DROP CONSTRAINT signing_record_kind_ck; ALTER TABLE signing_record ADD CONSTRAINT signing_record_kind_ck CHECK (operation_kind IN ('WITHDRAWAL', 'SWAP_BUY', 'SWAP_SELL'));
