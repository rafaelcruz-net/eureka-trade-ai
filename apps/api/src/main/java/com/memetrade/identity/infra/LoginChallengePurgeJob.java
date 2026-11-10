package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * BR2.5 (F3, AC3.1.1): a login challenge leaves {@code challenge-retention} (24 hours) after it expired, in any state
 * (ISSUED, CONSUMED or EXPIRED); a challenge that expired more recently stays. Each batch deletes by primary key, oldest
 * expiration first, through the index {@code login_challenge_expires_idx} (NFR3.5). Run only by
 * {@link IdentityJobRunner}.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoginChallengePurgeJob implements IdentityPurgeJob {

    private final JdbcClient jdbc;
    private final IdentityPurgeProperties settings;

    public LoginChallengePurgeJob(JdbcClient jdbc, IdentityPurgeProperties settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public IdentityPurge purge() {
        return IdentityPurge.LOGIN_CHALLENGE_PURGE;
    }

    @Override
    public Instant cutoff(Instant now) {
        return now.minus(settings.challengeRetention());
    }

    @Override
    public BatchCount deleteBatch(Instant cutoff, int limit) {
        int rows = jdbc.sql("""
                        delete from login_challenge
                        where nonce in (
                            select nonce from login_challenge
                            where expires_at <= :cutoff
                            order by expires_at
                            limit :limit)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .update();
        return new BatchCount(rows, 0);
    }
}
