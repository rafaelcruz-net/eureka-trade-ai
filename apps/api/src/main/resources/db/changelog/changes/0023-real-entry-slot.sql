--liquibase formatted sql

-- 261121-paper-real-unico, U2 u2-real-bot-pnl (C14, BR1.4, BR1.5, BR1.7, infrastructure-specification.md). Expand only:
-- a side table with the slot of each REAL signature, recorded once at the confirmation and never changed, and one index
-- on real_ledger_entry. No column is added to real_order nor to real_ledger_entry, and no old row is filled: an entry
-- without a slot row is SLOT_MISSING at read time and inherits the slot of the previous recorded entry (BR1.3). The
-- previous image neither reads nor writes anything of this migration (Migration0023AdditiveIT).

--changeset memetrade:0023-real-entry-slot
CREATE TABLE real_entry_slot (
    tx_signature varchar(90) PRIMARY KEY,
    slot         bigint      NOT NULL,
    recorded_at  timestamptz NOT NULL,
    CONSTRAINT real_entry_slot_slot_ck CHECK (slot >= 0)
);
--rollback DROP TABLE real_entry_slot;

--changeset memetrade:0023-real-entry-slot-append-only splitStatements:false
-- BR1.4: the first slot of a signature stays; UPDATE, DELETE and TRUNCATE are refused for every login, the owner
-- included, with the function of the ledger (0008, 0016).
CREATE TRIGGER real_entry_slot_no_update_delete BEFORE UPDATE OR DELETE ON real_entry_slot
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER real_entry_slot_no_truncate BEFORE TRUNCATE ON real_entry_slot
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
--rollback DROP TRIGGER real_entry_slot_no_truncate ON real_entry_slot; DROP TRIGGER real_entry_slot_no_update_delete ON real_entry_slot;

--changeset memetrade:0023-real-entry-slot-privileges splitStatements:false
-- Privilege matrix of infrastructure-specification.md, in the pattern of 0018 and 0019. The default privileges of
-- db-bootstrap give the application login SELECT, INSERT, UPDATE and DELETE on every new table; whatever its name (it
-- comes from .env), every non-owner grantee other than risk_retention loses every privilege and the INSERT holders (the
-- application) get back exactly SELECT and INSERT. TRUNCATE is revoked from the owner too.
REVOKE ALL ON real_entry_slot FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('real_entry_slot', 'SELECT, INSERT')
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

--changeset memetrade:0023-real-ledger-entry-wallet-created-idx runInTransaction:false
-- NFR1.18, PQ-10: the entries of one wallet in recording order, for the read in network order (C12), positions and
-- view. CONCURRENTLY keeps the confirmations writing during the deployment; PostgreSQL refuses it inside a transaction
-- block, hence runInTransaction:false, and each statement goes on its own (default splitStatements). An interrupted
-- build leaves an INVALID index and no row of this changeset: the DROP below removes it on the next run. Never
-- CREATE INDEX IF NOT EXISTS, which would silently keep an INVALID index (Migration0023IndexRestartIT).
DROP INDEX CONCURRENTLY IF EXISTS real_ledger_entry_wallet_created_idx;
CREATE INDEX CONCURRENTLY real_ledger_entry_wallet_created_idx ON real_ledger_entry (bot_wallet_id, created_at);
--rollback DROP INDEX CONCURRENTLY IF EXISTS real_ledger_entry_wallet_created_idx;
