package com.memetrade.identity.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base of the integration tests of the Identity purges (u2-retention, Passo 4): the real api context (PostgreSQL in a
 * container, the migrations of the application, the real ShedLock lock and publisher) with the fixed clock. Every test
 * starts without purge state and without held purge locks (the 5-minute minimum would otherwise skip the next test's
 * run) and removes the leftovers older than the cutoffs of the fixed clock, so the counts are its own; tests never
 * depend on each other or on the order.
 */
abstract class AbstractIdentityJobIT extends AbstractPostgresIT {

    @Autowired
    protected IdentityJobRunner runner;

    @Autowired
    protected ObjectMapper json;

    @BeforeEach
    void clearPurgeState() {
        releaseLocks();
        jdbc.sql("delete from identity_job_run").update();
        Instant now = clock.instant();
        jdbc.sql("delete from login_challenge where expires_at <= :cutoff")
                .param("cutoff", Timestamp.from(now.minus(Duration.ofHours(24))))
                .update();
        String ended = "coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at)) <= :cutoff";
        Timestamp sessionCutoff = Timestamp.from(now.minus(Duration.ofDays(30)));
        jdbc.sql(
                        "delete from session_used_refresh_digest where session_id in (select session_id from user_session where "
                                + ended + ")")
                .param("cutoff", sessionCutoff)
                .update();
        jdbc.sql("delete from user_session where " + ended)
                .param("cutoff", sessionCutoff)
                .update();
    }

    /**
     * Expires the purge locks instead of deleting them (ShedLock remembers which rows exist and then only updates), as
     * if the 5-minute minimum of the previous run had passed.
     */
    protected void releaseLocks() {
        jdbc.sql("""
                        update shedlock set lock_until = now() - interval '1 second'
                        where name in ('loginChallengePurge', 'sessionPurge')
                        """).update();
    }

    /** An ISSUED challenge of a test wallet that expires at {@code expiresAt}. */
    protected String challenge(Instant expiresAt) {
        String nonce = "it-purge-" + UUID.randomUUID();
        jdbc.sql("""
                        insert into login_challenge
                          (nonce, wallet_address, domain, chain, message, issued_at, expires_at, state, consumed_at)
                        values (:nonce, :wallet, 'localhost:3000', 'solana:mainnet', 'test message', :issued,
                                :expires, 'ISSUED', null)
                        """)
                .param("nonce", nonce)
                .param("wallet", TestWallet.generate().address())
                .param("issued", Timestamp.from(expiresAt.minus(Duration.ofMinutes(5))))
                .param("expires", Timestamp.from(expiresAt))
                .update();
        return nonce;
    }

    protected boolean challengeExists(String nonce) {
        return jdbc.sql("select count(*) from login_challenge where nonce = :n")
                        .param("n", nonce)
                        .query(Long.class)
                        .single()
                == 1L;
    }

    /** A session of {@code owner} that ended (never revoked) at {@code end}, with {@code digests} used digests. */
    protected UUID endedSession(TestAccounts.Seeded owner, Instant end, int digests) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_session
                          (session_id, user_id, tenant_id, role, refresh_token_digest, created_at,
                           refresh_expires_at, absolute_expires_at, state, revoked_at)
                        values (:id, :user, :tenant, 'BETA_USER', :digest, :created, :end, :end, 'EXPIRED', null)
                        """)
                .param("id", id)
                .param("user", owner.userId())
                .param("tenant", owner.tenantId())
                .param("digest", digest())
                .param("created", Timestamp.from(end.minus(Duration.ofHours(8))))
                .param("end", Timestamp.from(end))
                .update();
        for (int i = 0; i < digests; i++) {
            jdbc.sql("insert into session_used_refresh_digest (digest, session_id, used_at) values (:d, :s, :at)")
                    .param("d", digest())
                    .param("s", id)
                    .param("at", Timestamp.from(end.minus(Duration.ofHours(1))))
                    .update();
        }
        return id;
    }

    protected boolean sessionExists(UUID id) {
        return jdbc.sql("select count(*) from user_session where session_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                == 1L;
    }

    /** Ids of the outbox events of {@code purge} now present (to diff before and after a run). */
    protected Set<UUID> purgeEventIds(IdentityPurge purge) {
        return new HashSet<>(jdbc.sql("select event_id from audit_outbox where payload->>'action' = :action")
                .param("action", purge.action())
                .query(UUID.class)
                .list());
    }

    /** Payloads of the events of {@code purge} published since {@code before} was taken. */
    protected List<JsonNode> purgeEventsSince(IdentityPurge purge, Set<UUID> before) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (UUID id : purgeEventIds(purge)) {
            if (!before.contains(id)) {
                events.add(json.readTree(jdbc.sql("select payload::text from audit_outbox where event_id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single()));
            }
        }
        return events;
    }

    protected String lastResult(IdentityPurge purge) {
        return jdbc.sql("select last_result from identity_job_run where purge_id = :p")
                .param("p", purge.purgeId())
                .query(String.class)
                .single();
    }

    private static String digest() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }
}
