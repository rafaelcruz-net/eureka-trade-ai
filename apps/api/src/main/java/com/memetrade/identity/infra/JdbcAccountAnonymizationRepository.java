package com.memetrade.identity.infra;

import com.memetrade.identity.api.AccountAnonymizationPort.AnonymizationTarget;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.anonymization.AccountAnonymizationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * J1 of u5-anonymization (performance-design.md NFR3.1, NFR3.2): every statement by the key of the person or of the
 * address, through {@code app_user_pkey}, {@code user_session_user_idx}, the unique {@code wallet.user_id},
 * {@code login_challenge_wallet_idx} (migration 0015) and the primary key of {@code idempotency_record} (its scope starts
 * with the address). The full address never reaches a log or an exception.
 */
@Repository
public class JdbcAccountAnonymizationRepository implements AccountAnonymizationRepository {

    private final JdbcClient jdbc;

    public JdbcAccountAnonymizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AnonymizationTarget> findTarget(UUID userId) {
        return jdbc.sql("""
                        select u.user_id, u.tenant_id, u.role, u.status, w.anonymized_at
                        from app_user u
                        left join wallet w on w.user_id = u.user_id
                        where u.user_id = :user
                        """)
                .param("user", userId)
                .query((rs, n) -> new AnonymizationTarget(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        Role.valueOf(rs.getString("role")),
                        "ANONYMIZED".equals(rs.getString("status")),
                        instant(rs.getTimestamp("anonymized_at"))))
                .optional();
    }

    @Override
    public List<UUID> lockSessions(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select session_id from user_session
                        where user_id = :user and tenant_id = :tenant
                        order by session_id
                        for update
                        """)
                .param("user", userId)
                .param("tenant", tenantId)
                .query(UUID.class)
                .list();
    }

    @Override
    public Optional<String> walletOf(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select w.address from wallet w
                        join app_user u on u.user_id = w.user_id
                        where w.user_id = :user and u.tenant_id = :tenant and w.address is not null
                        """)
                .param("user", userId)
                .param("tenant", tenantId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<LockedAccount> lockAccount(UUID tenantId, UUID userId) {
        Optional<String> status = jdbc.sql("""
                        select status from app_user where user_id = :user and tenant_id = :tenant for update
                        """)
                .param("user", userId)
                .param("tenant", tenantId)
                .query(String.class)
                .optional();
        if (status.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("select address, anonymized_at from wallet where user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new LockedAccount(
                        status.get(),
                        Optional.ofNullable(rs.getString("address")),
                        instant(rs.getTimestamp("anonymized_at"))))
                .optional()
                .orElse(new LockedAccount(status.get(), Optional.empty(), Optional.empty())));
    }

    @Override
    public int revokeSessions(UUID tenantId, UUID userId, Instant now) {
        return jdbc.sql("""
                        update user_session set state = 'REVOKED', revoked_at = :now
                        where user_id = :user and tenant_id = :tenant and state = 'ACTIVE'
                        """)
                .param("now", Timestamp.from(now))
                .param("user", userId)
                .param("tenant", tenantId)
                .update();
    }

    @Override
    public int deleteLoginChallenges(String walletAddress) {
        int challenges = jdbc.sql("delete from login_challenge where wallet_address = :address")
                .param("address", walletAddress)
                .update();
        // The guest and the administrator challenge keep their answer (the message holds the address) until it expires.
        jdbc.sql("delete from idempotency_record where scope in (:guest, :admin)")
                .param("guest", scope(walletAddress, IdempotentOperation.AUTH_CHALLENGE))
                .param("admin", scope(walletAddress, IdempotentOperation.ADMIN_AUTH_CHALLENGE))
                .update();
        return challenges;
    }

    @Override
    public void removeWallet(UUID userId, Instant now) {
        jdbc.sql("""
                        update wallet set address = null, provider = null, anonymized_at = :now
                        where user_id = :user and address is not null
                        """).param("now", Timestamp.from(now)).param("user", userId).update();
    }

    @Override
    public void markAnonymized(UUID tenantId, UUID userId) {
        jdbc.sql("update app_user set status = 'ANONYMIZED' where user_id = :user and tenant_id = :tenant")
                .param("user", userId)
                .param("tenant", tenantId)
                .update();
    }

    @Override
    public Set<UUID> anonymizedAmong(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select user_id from app_user where user_id in (:ids) and status = 'ANONYMIZED'")
                .param("ids", List.copyOf(userIds))
                .query(UUID.class)
                .list());
    }

    /** The scope the idempotency mechanism uses for a call before login (IdempotencyScope.preLogin). */
    static String scope(String walletAddress, IdempotentOperation operation) {
        return "wallet:" + walletAddress + ":" + operation.operationName();
    }

    private static Optional<Instant> instant(Timestamp value) {
        return Optional.ofNullable(value).map(Timestamp::toInstant);
    }
}
