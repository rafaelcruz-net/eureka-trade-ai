--liquibase formatted sql

-- 261121-paper-real-unico, U2a u2-real-bot-pnl, Etapa 14b (decision of the founder of 2026-11-22, NFR1.26). Expand
-- only: a side table of the ledger (owner ledger) with the marks of zero of the position of a bot wallet in a mint. A
-- mark holds a fact derived from the quantity-only path (BR2.5), never a cost: in network order, the quantity of the
-- wallet in the mint was zero right after the row of the key it names. The view of the barrier reads each mint from its
-- latest mark on, and checks the mark against the count of the fills of the mint. A mark is only inserted, by the
-- writers of the ledger, in the transaction of the row that zeroed the position; a late confirmation that invalidates
-- one inserts a newer one. No column is added to real_ledger_entry, no old row is filled, and a mint without a mark is
-- read from its first fill. The previous image neither reads nor writes this table.

--changeset memetrade:0025-real-position-epoch
CREATE TABLE real_position_epoch (
    epoch_seq            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id        uuid         NOT NULL,
    mint                 varchar(44)  NOT NULL,
    zero_slot            bigint       NULL,
    zero_inherited       boolean      NULL,
    zero_tx_signature    varchar(90)  NULL,
    zero_created_at      timestamptz  NULL,
    zero_reference       varchar(200) NULL,
    zero_kind_rank       smallint     NULL,
    zero_idempotency_key varchar(200) NULL,
    fills_through        bigint       NOT NULL,
    window_from          timestamptz  NOT NULL,
    reason               varchar(16)  NOT NULL,
    recorded_at          timestamptz  NOT NULL,
    -- The key of the zero (NetworkOrderKey, BR1.1) is whole or absent (a full-read mark); the signature is required
    -- only of a row with a recorded slot.
    CONSTRAINT real_position_epoch_zero_ck CHECK (
        (zero_slot IS NULL AND zero_inherited IS NULL AND zero_tx_signature IS NULL AND zero_created_at IS NULL
            AND zero_reference IS NULL AND zero_kind_rank IS NULL AND zero_idempotency_key IS NULL
            AND fills_through = 0)
        OR (zero_slot IS NOT NULL AND zero_inherited IS NOT NULL AND zero_created_at IS NOT NULL
            AND zero_reference IS NOT NULL AND zero_kind_rank IS NOT NULL AND zero_idempotency_key IS NOT NULL
            AND (zero_inherited OR zero_tx_signature IS NOT NULL))),
    CONSTRAINT real_position_epoch_slot_ck CHECK (zero_slot >= 0),
    CONSTRAINT real_position_epoch_rank_ck CHECK (zero_kind_rank BETWEEN 0 AND 3),
    CONSTRAINT real_position_epoch_fills_ck CHECK (fills_through >= 0),
    CONSTRAINT real_position_epoch_reason_ck CHECK (reason IN ('ZERO', 'OUT_OF_ORDER', 'EARLY_ROW'))
);
-- The latest mark of each mint of a wallet: the view reads it with DISTINCT ON over this index.
CREATE INDEX real_position_epoch_wallet_idx ON real_position_epoch (bot_wallet_id, mint, epoch_seq DESC);
--rollback DROP TABLE real_position_epoch;

--changeset memetrade:0025-real-position-epoch-append-only splitStatements:false
-- Insert-only, like the ledger and the slots: UPDATE, DELETE and TRUNCATE are refused for every login, the owner
-- included, with the function of the ledger (0008, 0016, 0023).
CREATE TRIGGER real_position_epoch_no_update_delete BEFORE UPDATE OR DELETE ON real_position_epoch
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER real_position_epoch_no_truncate BEFORE TRUNCATE ON real_position_epoch
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
--rollback DROP TRIGGER real_position_epoch_no_truncate ON real_position_epoch; DROP TRIGGER real_position_epoch_no_update_delete ON real_position_epoch;

--changeset memetrade:0025-real-position-epoch-privileges splitStatements:false
-- Privilege matrix in the pattern of 0018, 0019 and 0023. The default privileges of db-bootstrap give the application
-- login SELECT, INSERT, UPDATE and DELETE on every new table; whatever its name (it comes from .env), every non-owner
-- grantee other than risk_retention loses every privilege and the INSERT holders (the application) get back exactly
-- SELECT and INSERT. TRUNCATE is revoked from the owner too. The identity of epoch_seq needs no grant on its sequence.
REVOKE ALL ON real_position_epoch FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('real_position_epoch', 'SELECT, INSERT')
        ) AS s(table_name, app_privileges)
    LOOP
        SELECT array_agg(DISTINCT r.rolname) INTO writers
        FROM pg_class c
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        JOIN pg_roles r ON r.oid = a.grantee
        WHERE c.oid = spec.table_name::regclass AND a.privilege_type = 'INSERT' AND a.grantee <> c.relowner;

        FOR grantee_name IN
            SELECT DISTINCT r.rolname
            FROM pg_class c
            CROSS JOIN LATERAL aclexplode(c.relacl) a
            JOIN pg_roles r ON r.oid = a.grantee
            WHERE c.oid = spec.table_name::regclass AND a.grantee <> c.relowner AND r.rolname <> 'risk_retention'
        LOOP
            EXECUTE format('REVOKE ALL ON %I FROM %I', spec.table_name, grantee_name);
            IF spec.app_privileges <> '' AND writers IS NOT NULL AND grantee_name = ANY (writers) THEN
                EXECUTE format('GRANT %s ON %I TO %I', spec.app_privileges, spec.table_name, grantee_name);
            END IF;
        END LOOP;
        EXECUTE format('REVOKE TRUNCATE ON %I FROM CURRENT_USER', spec.table_name);
    END LOOP;
END;
$$;
--rollback SELECT 1;
