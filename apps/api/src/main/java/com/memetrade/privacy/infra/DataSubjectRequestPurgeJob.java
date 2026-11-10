package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.request.DataRequestPurgeStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * BR4.15 (F4 step 3, AC5.8.1, NFR7.1): a request leaves {@code retentionMonths} calendar months (12, in UTC) after it
 * was closed, with its verification first and so its contact; a request closed more recently stays and an open one
 * never leaves. Each batch deletes by primary key, oldest closure first, through the partial index
 * {@code data_subject_request_closed_idx}. Run only by {@link PrivacyJobRunner}.
 */
public class DataSubjectRequestPurgeJob implements PrivacyPurgeJob {

    private final DataRequestPurgeStore store;
    private final int retentionMonths;

    public DataSubjectRequestPurgeJob(DataRequestPurgeStore store, int retentionMonths) {
        this.store = Objects.requireNonNull(store, "store");
        if (retentionMonths < 1) {
            throw new IllegalArgumentException("retentionMonths must be positive");
        }
        this.retentionMonths = retentionMonths;
    }

    @Override
    public PrivacyPurge purge() {
        return PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE;
    }

    /** Calendar months in UTC: the 29th of February goes back to the 28th of a common year. */
    @Override
    public Instant cutoff(Instant now) {
        return now.atZone(ZoneOffset.UTC).minusMonths(retentionMonths).toInstant();
    }

    @Override
    public BatchCount deleteBatch(Instant cutoff, int limit) {
        DataRequestPurgeStore.PurgeBatch batch = store.deleteClosedUpTo(cutoff, limit);
        return new BatchCount(batch.rows(), batch.related());
    }

    @Override
    public Optional<String> relatedDetail() {
        return Optional.of("verificationsDeleted");
    }
}
