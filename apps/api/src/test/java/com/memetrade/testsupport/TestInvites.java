package com.memetrade.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builder of invitation rows for the U3 integration tests (Passo 2). Codes are generated here, in test code, with the
 * same shape as production (32 random bytes, base64url without padding); only their digest and last 4 characters are
 * written, exactly like the production repository. Tenants are created per test unless the test names one.
 */
public final class TestInvites {

    /** Fixed identifier of the tenant "Beta" seeded by the migration 0007 (BR1.7). */
    public static final UUID BETA_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000b3");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestInvites() {}

    /** A fresh, well-formed invitation code (43 characters of the base64url alphabet). */
    public static String newCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String digest(String code) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** Inserts an ACTIVE tenant of its own and returns its id. */
    public static UUID tenant(JdbcClient jdbc, String status) {
        UUID tenantId = UUID.randomUUID();
        jdbc.sql("insert into tenant (tenant_id, name, status, created_at) values (:id, 'Invite test', :s, :now)")
                .param("id", tenantId)
                .param("s", status)
                .param("now", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
                .update();
        return tenantId;
    }

    public static Builder pending(JdbcClient jdbc) {
        return new Builder(jdbc);
    }

    /** What the builder inserted; the code is known only to the test. */
    public record Created(UUID inviteId, String code, UUID tenantId, Instant expiresAt) {}

    public static final class Builder {

        private final JdbcClient jdbc;
        private UUID tenantId;
        private String code = newCode();
        private Instant expiresAt = MutableClock.DEFAULT_START.plus(Duration.ofDays(1));
        private Instant createdAt;
        private Instant revokedAt;

        private Builder(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        public Builder tenant(UUID tenant) {
            this.tenantId = tenant;
            return this;
        }

        public Builder code(String value) {
            this.code = value;
            return this;
        }

        public Builder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        public Builder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public Builder revokedAt(Instant value) {
            this.revokedAt = value;
            return this;
        }

        public Created insert() {
            UUID tenant = tenantId != null ? tenantId : TestInvites.tenant(jdbc, "ACTIVE");
            Instant created = createdAt != null ? createdAt : expiresAt.minus(Duration.ofDays(2));
            UUID inviteId = UUID.randomUUID();
            jdbc.sql("""
                            insert into invite (invite_id, tenant_id, code_digest, code_suffix, status, issued_by,
                                                created_at, expires_at, revoked_at)
                            values (:id, :tenant, :digest, :suffix, :status, 'Adm1…test', :created, :expires, :revoked)
                            """)
                    .param("id", inviteId)
                    .param("tenant", tenant)
                    .param("digest", digest(code))
                    .param("suffix", code.substring(code.length() - 4))
                    .param("status", revokedAt == null ? "PENDING" : "REVOKED")
                    .param("created", Timestamp.from(created))
                    .param("expires", Timestamp.from(expiresAt))
                    .param("revoked", revokedAt == null ? null : Timestamp.from(revokedAt))
                    .update();
            return new Created(inviteId, code, tenant, expiresAt);
        }
    }
}
