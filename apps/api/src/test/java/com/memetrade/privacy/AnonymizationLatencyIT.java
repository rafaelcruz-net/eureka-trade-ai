package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of u5-anonymization (NFR3.1; performance-design.md): 20 accounts, each with 20 bots, 100 sessions and 50
 * comments (seeded in bulk), are anonymized one after the other; the 95th percentile of the duration of the whole call
 * (transaction, locks and commit) stays at or below 2 s.
 */
class AnonymizationLatencyIT extends AbstractAnonymizationIT {

    private static final int ACCOUNTS = 20;

    @Test
    void theNinetyFifthPercentileOfTwentyAnonymizationsIsAtMostTwoSeconds() throws Exception {
        List<Deletion> deletions = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            Deletion deletion = deletionInProgress();
            seedInBulk(deletion.guest().principal());
            deletions.add(deletion);
        }
        List<Long> millis = new ArrayList<>();

        for (Deletion deletion : deletions) {
            long started = System.nanoTime();
            var result = anonymize(deletion);
            millis.add((System.nanoTime() - started) / 1_000_000);
            assertThat(result.botsStopped()).isEqualTo(10);
            assertThat(result.sessionsRevoked()).isEqualTo(101);
            assertThat(result.feedbackDeleted()).isEqualTo(50);
        }

        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(0.95 * millis.size()) - 1);
        assertThat(p95).as("p95 of %s ms", millis).isLessThanOrEqualTo(2000L);
    }

    /** 20 bots (10 ACTIVE, 10 DRAFT), 100 more ACTIVE sessions and 50 comments, synthetic. */
    private void seedInBulk(Principal person) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, mode, name, template_id, template_version,
                                         state, pause_reasons, draft_step, created_at, updated_at)
                        select gen_random_uuid(), :t, :u, 'PAPER', 'Bot sintético ' || g, 'MOMENTUM_VOLUME', 'v1',
                               case when g <= 10 then 'ACTIVE' else 'DRAFT' end, '', 1, now(), now()
                        from generate_series(1, 20) g
                        """).param("t", person.tenantId()).param("u", person.userId()).update();
        jdbc.sql("""
                        insert into user_session (session_id, user_id, tenant_id, role, refresh_token_digest, created_at,
                                                  refresh_expires_at, absolute_expires_at, state)
                        select gen_random_uuid(), :u, :t, 'BETA_USER', md5(random()::text) || md5(random()::text), cast(:now as timestamptz),
                               cast(:now as timestamptz) + interval '8 hours',
                               cast(:now as timestamptz) + interval '12 hours', 'ACTIVE'
                        from generate_series(1, 100)
                        """)
                .param("u", person.userId())
                .param("t", person.tenantId())
                .param("now", now)
                .update();
        jdbc.sql("""
                        insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                        select gen_random_uuid(), :t, :u, 'S3', 'Comentário sintético ' || g, :now
                        from generate_series(1, 50) g
                        """)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("now", now)
                .update();
    }
}
