--liquibase formatted sql

-- U3 u3-reacceptance of 261109-termos-lgpd (reliability-design.md NFR5.1, infrastructure-specification.md): expand
-- only. terms_acceptance starts to receive acceptances without an invitation (REACCEPTANCE): invite_id accepts null,
-- a source column with its default, two CHECK constraints and the unique constraint (user_id, terms_version), which is
-- also the index of the reacceptance lock. No existing row is rewritten and no object is removed; the REVOKE of
-- UPDATE, DELETE and TRUNCATE of 0007 stays as it is (NFR2.3). Every changeset declares its rollback.
-- The rollback of the DROP NOT NULL only works before the first new acceptance: once a REACCEPTANCE row with a null
-- invite_id exists, the column never goes back to NOT NULL and the application is rolled back by the previous image,
-- without undoing this migration (expand-contract). ReacceptanceMigrationTest reads this file.

--changeset memetrade:0013-terms-acceptance-invite-nullable
-- BR3.4: a REACCEPTANCE has no invitation.
ALTER TABLE terms_acceptance ALTER COLUMN invite_id DROP NOT NULL;
--rollback ALTER TABLE terms_acceptance ALTER COLUMN invite_id SET NOT NULL;

--changeset memetrade:0013-terms-acceptance-source
-- entities.md TermsAcceptance.source: every existing row came from an invitation, so the default is INVITE.
ALTER TABLE terms_acceptance ADD COLUMN source varchar(16) NOT NULL DEFAULT 'INVITE';
--rollback ALTER TABLE terms_acceptance DROP COLUMN source;

--changeset memetrade:0013-terms-acceptance-source-ck
ALTER TABLE terms_acceptance ADD CONSTRAINT terms_acceptance_source_ck CHECK (source IN ('INVITE', 'REACCEPTANCE'));
--rollback ALTER TABLE terms_acceptance DROP CONSTRAINT terms_acceptance_source_ck;

--changeset memetrade:0013-terms-acceptance-source-invite-ck
-- An INVITE acceptance always names its invitation; a REACCEPTANCE never does.
ALTER TABLE terms_acceptance ADD CONSTRAINT terms_acceptance_source_invite_ck
    CHECK ((source = 'INVITE') = (invite_id IS NOT NULL));
--rollback ALTER TABLE terms_acceptance DROP CONSTRAINT terms_acceptance_source_invite_ck;

--changeset memetrade:0013-terms-acceptance-user-version-uq
-- BR3.5, NFR3.2, NFR3.5: at most one acceptance per person and version; the unique index serves the EXISTS of the lock
-- and resolves two concurrent acceptances (INSERT ... ON CONFLICT DO NOTHING).
ALTER TABLE terms_acceptance ADD CONSTRAINT uq_terms_acceptance_user_version UNIQUE (user_id, terms_version);
--rollback ALTER TABLE terms_acceptance DROP CONSTRAINT uq_terms_acceptance_user_version;
