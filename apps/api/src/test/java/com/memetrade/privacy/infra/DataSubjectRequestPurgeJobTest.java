package com.memetrade.privacy.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.request.DataRequestPurgeStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 3 of u4-data-requests, written BEFORE the code: the purge of the closed requests (rules.md BR4.15; AC5.8.1;
 * reliability-design.md NFR7.1).
 *
 * <ul>
 *   <li>a request closed 12 calendar months ago or more (UTC) leaves with its verification; a more recent one stays;
 *   <li>an open request never leaves, however old;
 *   <li>a batch deletes at most its limit, oldest closure first, and counts the verifications deleted with it.
 * </ul>
 */
@Tag("u4-data-requests")
class DataSubjectRequestPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:00:00Z");

    private final InMemoryClosed store = new InMemoryClosed();
    private final DataSubjectRequestPurgeJob job = new DataSubjectRequestPurgeJob(store, 12);

    @Test
    void theJobIsTheDataSubjectRequestPurgeWithItsEventAndItsDependentRows() {
        assertThat(job.purge()).isEqualTo(PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE);
        assertThat(job.purge().purgeId()).isEqualTo("dataSubjectRequestPurge");
        assertThat(job.purge().action()).isEqualTo(AuditActions.DATA_REQUESTS_PURGED);
        assertThat(job.relatedDetail()).contains("verificationsDeleted");
    }

    @Test
    void theCutoffIsTwelveCalendarMonthsBeforeNowInUtc() {
        assertThat(job.cutoff(NOW)).isEqualTo(Instant.parse("2025-11-10T04:00:00Z"));
        assertThat(job.cutoff(Instant.parse("2028-02-29T04:00:00Z"))).isEqualTo(Instant.parse("2027-02-28T04:00:00Z"));
    }

    @Test
    void aRequestClosedTwelveMonthsAgoLeavesWithItsVerificationAndANewerOneStays() {
        Instant cutoff = job.cutoff(NOW);
        UUID atTheLimit = store.closed(cutoff, true);
        UUID older = store.closed(cutoff.minusSeconds(86_400), false);
        UUID newer = store.closed(cutoff.plusSeconds(1), true);

        DataSubjectRequestPurgeJob.BatchCount count = job.deleteBatch(cutoff, 1000);

        assertThat(count.rows()).isEqualTo(2);
        assertThat(count.related()).isEqualTo(1);
        assertThat(store.ids()).containsExactly(newer);
        assertThat(store.verifications).containsExactly(newer);
        assertThat(store.ids()).doesNotContain(atTheLimit, older);
    }

    @Test
    void anOpenRequestNeverLeavesHoweverOld() {
        UUID open = store.open();

        assertThat(job.deleteBatch(job.cutoff(NOW), 1000).rows()).isZero();
        assertThat(store.ids()).containsExactly(open);
    }

    @Test
    void aBatchDeletesAtMostItsLimitOldestFirst() {
        Instant cutoff = job.cutoff(NOW);
        UUID oldest = store.closed(cutoff.minusSeconds(300), false);
        UUID middle = store.closed(cutoff.minusSeconds(200), false);
        UUID youngest = store.closed(cutoff.minusSeconds(100), false);

        DataSubjectRequestPurgeJob.BatchCount first = job.deleteBatch(cutoff, 2);

        assertThat(first.rows()).isEqualTo(2);
        assertThat(store.ids()).containsExactly(youngest).doesNotContain(oldest, middle);
        assertThat(job.deleteBatch(cutoff, 2).rows()).isEqualTo(1);
        assertThat(store.ids()).isEmpty();
    }

    @Test
    void theRetentionMustBePositive() {
        assertThatThrownBy(() -> new DataSubjectRequestPurgeJob(store, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Closed and open requests with their verifications, deleted by the same rule as the SQL of the real store. */
    private static final class InMemoryClosed implements DataRequestPurgeStore {

        private record Row(UUID id, Instant closedAt) {}

        private final List<Row> rows = new ArrayList<>();
        private final List<UUID> verifications = new ArrayList<>();

        UUID closed(Instant closedAt, boolean withVerification) {
            UUID id = UUID.randomUUID();
            rows.add(new Row(id, closedAt));
            if (withVerification) {
                verifications.add(id);
            }
            return id;
        }

        UUID open() {
            UUID id = UUID.randomUUID();
            rows.add(new Row(id, null));
            return id;
        }

        List<UUID> ids() {
            return rows.stream().map(Row::id).toList();
        }

        @Override
        public PurgeBatch deleteClosedUpTo(Instant cutoff, int limit) {
            List<Row> gone = rows.stream()
                    .filter(row -> row.closedAt() != null && !row.closedAt().isAfter(cutoff))
                    .sorted(Comparator.comparing(Row::closedAt))
                    .limit(limit)
                    .toList();
            int related = 0;
            for (Row row : gone) {
                if (verifications.remove(row.id())) {
                    related++;
                }
                rows.remove(row);
            }
            return new PurgeBatch(gone.size(), related);
        }
    }
}
