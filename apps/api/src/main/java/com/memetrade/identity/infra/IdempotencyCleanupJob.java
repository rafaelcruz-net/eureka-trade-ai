package com.memetrade.identity.infra;

import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.shared.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly cleanup of expired idempotency records in batches of up to 1,000 (reliability-design.md). */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService service;
    private final IdempotencyProperties properties;

    public IdempotencyCleanupJob(IdempotencyService service, IdempotencyProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${memetrade.idempotency.cleanup-interval}", initialDelayString = "PT1M")
    public void run() {
        CorrelationId.runWithNew(this::purgeAll);
    }

    int purgeAll() {
        int total = 0;
        try {
            int deleted;
            do {
                deleted = service.purgeExpired(properties.cleanupBatchSize());
                total += deleted;
            } while (deleted == properties.cleanupBatchSize());
            if (total > 0) {
                log.info("Expired idempotency records deleted: {}", total);
            }
        } catch (DataAccessException failure) {
            log.error(
                    "Idempotency cleanup failed: {}; next cycle retries",
                    failure.getClass().getSimpleName());
        }
        return total;
    }
}
