package com.memetrade.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 3 (migration 0007-identity-access): state/date coherence of the invitation, uniqueness, the named wallet
 * constraint, the insert-only acceptance table and the repeatable seed of the tenant "Beta" (BR1.7, BR3.3, BR3.4,
 * NFR4.2, NFR4.3, NFR10.4). The rollback test (previous queries on the new schema) is in {@link MigrationsIT}.
 */
class IdentityAccessMigrationIT extends AbstractU3IT {

    private static final Instant T = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void aPendingInvitationHasNoUseOrRevocationDatesAndExpiredIsNeverStored() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");

        assertThatCode(() -> insertInvite(tenant, "PENDING", null, null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertInvite(tenant, "PENDING", T, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvite(tenant, "PENDING", null, null, T))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvite(tenant, "EXPIRED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvite(tenant, "pending", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usedNeedsUseDateAndPersonAndRevokedNeedsRevocationDateOnly() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        UUID user = TestAccounts.betaUser(jdbc, TestWallet.generate().address()).userId();

        assertThatThrownBy(() -> insertInvite(tenant, "USED", T, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvite(tenant, "USED", null, user, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvite(tenant, "REVOKED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvite(tenant, "REVOKED", T, user, T))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertInvite(tenant, "REVOKED", null, null, T)).doesNotThrowAnyException();
        assertThatCode(() -> insertInvite(tenant, "USED", T, user, null)).doesNotThrowAnyException();
    }

    @Test
    void theValidityStaysBetweenOneHourAndThirtyDaysAfterCreation() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");

        assertThatCode(() -> insert(tenant, digestOf(), T, T.plus(Duration.ofHours(1))))
                .doesNotThrowAnyException();
        assertThatCode(() -> insert(tenant, digestOf(), T, T.plus(Duration.ofDays(30))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> insert(
                        tenant, digestOf(), T, T.plus(Duration.ofHours(1)).minusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(
                        tenant, digestOf(), T, T.plus(Duration.ofDays(30)).plusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theCodeDigestAndThePersonOfAUsedInvitationAreUnique() {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        String digest = digestOf();
        insert(tenant, digest, T, T.plus(Duration.ofDays(1)));
        UUID user = TestAccounts.betaUser(jdbc, TestWallet.generate().address()).userId();
        insertInvite(tenant, "USED", T, user, null);

        assertThatThrownBy(() -> insert(tenant, digest, T, T.plus(Duration.ofDays(1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_invite_code_digest");
        assertThatThrownBy(() -> insertInvite(tenant, "USED", T, user, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_invite_used_by");
        assertThatThrownBy(() -> insert(tenant, "not-a-digest", T, T.plus(Duration.ofDays(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theWalletAddressHasExactlyOneUniqueConstraintNamedUqWalletAddress() {
        List<String> unique = jdbc.sql("""
                        select c.conname from pg_constraint c
                        where c.conrelid = 'wallet'::regclass and c.contype = 'u'
                          and c.conkey = array[(select attnum from pg_attribute
                                                where attrelid = 'wallet'::regclass and attname = 'address')]::int2[]
                        """).query(String.class).list();

        assertThat(unique).containsExactly("uq_wallet_address");
        String address = TestWallet.generate().address();
        TestAccounts.betaUser(jdbc, address);
        assertThatThrownBy(() -> TestAccounts.betaUser(jdbc, address))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_wallet_address");
    }

    @Test
    void theApplicationMayInsertAnAcceptanceButNeverUpdateOrDeleteIt() {
        // u3-reacceptance: without the acceptance of the fixtures, so this INVITE row is the only one of (person, v1).
        TestAccounts.Seeded account = TestAccounts.betaUserWithoutAcceptance(
                jdbc, TestWallet.generate().address());
        UUID invite = insertInvite(account.tenantId(), "USED", T, account.userId(), null);
        UUID acceptance = UUID.randomUUID();
        jdbc.sql("""
                        insert into terms_acceptance (acceptance_id, user_id, tenant_id, terms_version, accepted_at,
                                                      invite_id)
                        values (:id, :user, :tenant, 'v1', :at, :invite)
                        """)
                .param("id", acceptance)
                .param("user", account.userId())
                .param("tenant", account.tenantId())
                .param("at", Timestamp.from(T))
                .param("invite", invite)
                .update();

        assertThatThrownBy(() -> jdbc.sql("update terms_acceptance set terms_version = 'v2' where acceptance_id = :id")
                        .param("id", acceptance)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table terms_acceptance");
        assertThatThrownBy(() -> jdbc.sql("delete from terms_acceptance where acceptance_id = :id")
                        .param("id", acceptance)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for table terms_acceptance");
        assertThat(jdbc.sql("select terms_version from terms_acceptance where acceptance_id = :id")
                        .param("id", acceptance)
                        .query(String.class)
                        .single())
                .isEqualTo("v1");
    }

    @Test
    void theTenantBetaIsSeededOnceWithItsFixedIdentifierAndASecondSeedChangesNothing() {
        String seed = """
                INSERT INTO tenant (tenant_id, name, status, created_at)
                SELECT '00000000-0000-4000-8000-0000000000b3'::uuid, 'Beta', 'ACTIVE', now()
                WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE tenant_id = '00000000-0000-4000-8000-0000000000b3'::uuid)
                """;
        Timestamp before = createdAtOfBeta();

        int inserted = connect(OWNER).sql(seed).update();

        assertThat(inserted).isZero();
        assertThat(jdbc.sql("select count(*) from tenant where name = 'Beta' and status = 'ACTIVE' and tenant_id = :id")
                        .param("id", TestInvites.BETA_TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(createdAtOfBeta()).isEqualTo(before);
    }

    @Test
    void applyingTheChangelogTwiceOnANewDatabaseKeepsOneTenantBetaAndOneWalletConstraint() {
        String dbName = "u3_twice_" + UUID.randomUUID().toString().replace("-", "");
        connect(SUPERUSER).sql("CREATE DATABASE " + dbName).update();
        String url = POSTGRES.getJdbcUrl().replace("/memetrade", "/" + dbName);
        bootstrap(dbName);
        migrate(url);
        migrate(url);

        JdbcClient owner = connect(OWNER, url);
        assertThat(owner.sql("select count(*) from tenant where name = 'Beta'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(owner.sql("select count(*) from pg_constraint where conrelid = 'wallet'::regclass and contype = 'u'"
                                + " and conname in ('uq_wallet_address', 'wallet_address_key')")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private Timestamp createdAtOfBeta() {
        return jdbc.sql("select created_at from tenant where tenant_id = :id")
                .param("id", TestInvites.BETA_TENANT)
                .query(Timestamp.class)
                .single();
    }

    private static String digestOf() {
        return TestInvites.digest(TestInvites.newCode());
    }

    private void insert(UUID tenant, String digest, Instant created, Instant expires) {
        jdbc.sql("""
                        insert into invite (invite_id, tenant_id, code_digest, code_suffix, status, issued_by,
                                            created_at, expires_at)
                        values (:id, :tenant, :digest, 'abcd', 'PENDING', 'Adm1…test', :created, :expires)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", tenant)
                .param("digest", digest)
                .param("created", Timestamp.from(created))
                .param("expires", Timestamp.from(expires))
                .update();
    }

    private UUID insertInvite(UUID tenant, String status, Instant usedAt, UUID usedBy, Instant revokedAt) {
        UUID inviteId = UUID.randomUUID();
        jdbc.sql("""
                        insert into invite (invite_id, tenant_id, code_digest, code_suffix, status, issued_by,
                                            created_at, expires_at, used_at, used_by_user_id, revoked_at)
                        values (:id, :tenant, :digest, 'abcd', :status, 'Adm1…test', :created, :expires,
                                :used, :usedBy, :revoked)
                        """)
                .param("id", inviteId)
                .param("tenant", tenant)
                .param("digest", digestOf())
                .param("status", status)
                .param("created", Timestamp.from(T))
                .param("expires", Timestamp.from(T.plus(Duration.ofDays(1))))
                .param("used", usedAt == null ? null : Timestamp.from(usedAt))
                .param("usedBy", usedBy)
                .param("revoked", revokedAt == null ? null : Timestamp.from(revokedAt))
                .update();
        return inviteId;
    }
}
