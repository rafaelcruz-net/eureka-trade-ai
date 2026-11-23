--liquibase formatted sql

-- 261121-paper-real-unico, U2 u2-real-bot-pnl (decision F-4 of the founder, reliability-design.md "Fotografia do dia
-- adiada", infrastructure-specification.md). Expand only: a small table of the barrier (owner riskcontrol) with the mark
-- that the round of the day could not take the photo of a wallet because a cost was unknown. While the mark of today
-- exists without a photo, the first buy of the day is refused and takes no photo. It holds no result of a read (ADR-004)
-- and no P&L. Purged like real_risk_day, by its instant. The previous image neither reads nor writes it.

--changeset memetrade:0024-real-risk-day-deferred
CREATE TABLE real_risk_day_deferred (
    tenant_id     uuid        NOT NULL REFERENCES tenant (tenant_id),
    bot_wallet_id uuid        NOT NULL,
    day           date        NOT NULL,
    reason        varchar(20) NOT NULL,
    deferred_at   timestamptz NOT NULL,
    CONSTRAINT real_risk_day_deferred_pk PRIMARY KEY (bot_wallet_id, day),
    CONSTRAINT real_risk_day_deferred_reason_ck CHECK (reason = 'COST_UNKNOWN')
);
CREATE INDEX real_risk_day_deferred_purge_idx ON real_risk_day_deferred (deferred_at);
--rollback DROP TABLE real_risk_day_deferred;

--changeset memetrade:0024-real-risk-day-deferred-privileges splitStatements:false
-- Privilege matrix of infrastructure-specification.md, in the pattern of 0018: the application keeps SELECT and INSERT
-- (the first mark of a day stays: ON CONFLICT DO NOTHING); every other non-owner grantee but risk_retention loses
-- everything; TRUNCATE is revoked from the owner too; risk_retention reads and deletes for the purge.
REVOKE ALL ON real_risk_day_deferred FROM PUBLIC;
DO $$
DECLARE
    spec record;
    grantee_name text;
    writers text[];
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('real_risk_day_deferred', 'SELECT, INSERT')
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
GRANT SELECT, DELETE ON real_risk_day_deferred TO risk_retention;
--rollback REVOKE SELECT, DELETE ON real_risk_day_deferred FROM risk_retention;
