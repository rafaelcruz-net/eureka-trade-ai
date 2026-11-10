package com.memetrade.testsupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Seeds tenant, BETA_USER account and public wallet rows for integration tests (never real wallets). */
public final class TestAccounts {

    private TestAccounts() {}

    public record Seeded(UUID tenantId, UUID userId, String walletAddress) {}

    /**
     * u3-reacceptance: the effective current version of the default catalog ({@code classpath:terms/}) in the tests;
     * {@code U3ContextIT} asserts it. Seeded people accept it, so the reacceptance lock lets them through like a person
     * who joined by invitation.
     */
    public static final String CURRENT_TERMS_VERSION = "v1";

    public static Seeded betaUser(JdbcClient jdbc, String walletAddress) {
        return seed(jdbc, walletAddress, "ACTIVE", "ACTIVE");
    }

    /** u3-reacceptance: a BETA_USER without any acceptance of the terms (the lock refuses it outside the allowed routes). */
    public static Seeded betaUserWithoutAcceptance(JdbcClient jdbc, String walletAddress) {
        return seedPerson(jdbc, walletAddress, "ACTIVE", "ACTIVE");
    }

    /**
     * u3-reacceptance: a REACCEPTANCE row (no invitation, as the cross CHECK allows) of {@code version} for the person,
     * at the fixed instant of the fixtures; nothing when the person already accepted that version.
     */
    public static void acceptTerms(JdbcClient jdbc, UUID tenantId, UUID userId, String version) {
        acceptTerms(jdbc, tenantId, userId, version, Instant.parse("2026-01-01T00:00:00Z"));
    }

    public static void acceptTerms(JdbcClient jdbc, UUID tenantId, UUID userId, String version, Instant acceptedAt) {
        jdbc.sql("""
                        insert into terms_acceptance
                            (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
                        values (:id, :user, :tenant, :version, :at, null, 'REACCEPTANCE')
                        on conflict on constraint uq_terms_acceptance_user_version do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("tenant", tenantId)
                .param("version", version)
                .param("at", Timestamp.from(acceptedAt))
                .update();
    }

    public static Seeded seed(JdbcClient jdbc, String walletAddress, String tenantStatus, String userStatus) {
        Seeded seeded = seedPerson(jdbc, walletAddress, tenantStatus, userStatus);
        acceptTerms(jdbc, seeded.tenantId(), seeded.userId(), CURRENT_TERMS_VERSION);
        return seeded;
    }

    private static Seeded seedPerson(JdbcClient jdbc, String walletAddress, String tenantStatus, String userStatus) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.sql("insert into tenant (tenant_id, name, status, created_at) values (:id, 'Test tenant', :status, :now)")
                .param("id", tenantId)
                .param("status", tenantStatus)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into app_user (user_id, tenant_id, role, status, created_at)
                        values (:id, :tenant, 'BETA_USER', :status, :now)
                        """)
                .param("id", userId)
                .param("tenant", tenantId)
                .param("status", userStatus)
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into wallet (wallet_id, user_id, address, provider, verified_at)
                        values (:id, :user, :address, 'PHANTOM', :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("address", walletAddress)
                .param("now", now)
                .update();
        return new Seeded(tenantId, userId, walletAddress);
    }

    public static void setUserStatus(JdbcClient jdbc, UUID userId, String status) {
        jdbc.sql("update app_user set status = :s where user_id = :id")
                .param("s", status)
                .param("id", userId)
                .update();
    }

    public static void setTenantStatus(JdbcClient jdbc, UUID tenantId, String status) {
        jdbc.sql("update tenant set status = :s where tenant_id = :id")
                .param("s", status)
                .param("id", tenantId)
                .update();
    }
}
