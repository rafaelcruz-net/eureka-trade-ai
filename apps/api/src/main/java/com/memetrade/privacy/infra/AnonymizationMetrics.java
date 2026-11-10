package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.anonymization.AnonymizationOutcomes;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Observation of the anonymization (observability-design.md NFR8.1, NFR8.3, NFR8.4; monitoring-design.md). No label
 * names a person or a request:
 *
 * <ul>
 *   <li>{@code memetrade_accounts_anonymized_total}: incremented AFTER THE COMMIT, so an anonymization rolled back is
 *       never counted;
 *   <li>{@code memetrade_account_anonymization_failures_total{reason}}: {@code reason} is LOCK_TIMEOUT or ROLLED_BACK,
 *       both registered at zero at start-up so the first failure is already an increase; incremented in
 *       {@code afterCompletion} with a rollback, outside the undone transaction. Refusals (404, 409, 422) and
 *       repetitions are not failures;
 *   <li>{@code memetrade_account_anonymization_seconds}: the whole transaction, lock waits included.
 * </ul>
 *
 * One INFO "Account anonymized" after the commit and one ERROR "Account anonymization failed" after the rollback, with
 * the request id, the counts and the class of the exception only: never the wallet nor the person.
 */
@Component
public class AnonymizationMetrics implements AnonymizationOutcomes {

    private static final Logger log = LoggerFactory.getLogger(AnonymizationMetrics.class);

    private final Counter anonymized;
    private final Map<FailureReason, Counter> failures = new EnumMap<>(FailureReason.class);
    private final Timer duration;

    public AnonymizationMetrics(MeterRegistry registry) {
        this.anonymized = Counter.builder("memetrade.accounts.anonymized")
                .description("Accounts anonymized (counted after the commit)")
                .register(registry);
        for (FailureReason reason : FailureReason.values()) {
            failures.put(
                    reason,
                    Counter.builder("memetrade.account.anonymization.failures")
                            .description("Anonymizations rolled back; the request stays IN_PROGRESS")
                            .tag("reason", reason.name())
                            .register(registry));
        }
        this.duration = Timer.builder("memetrade.account.anonymization")
                .description("Duration of the anonymization transaction, lock waits included")
                .register(registry);
    }

    @Override
    public Attempt start(UUID requestId) {
        Tracked attempt = new Tracked(requestId, System.nanoTime());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(attempt);
        } else {
            attempt.immediate = true;
        }
        return attempt;
    }

    /** One attempt, closed by its transaction (or at once when no transaction synchronisation exists). */
    private final class Tracked implements Attempt, TransactionSynchronization {
        private final UUID requestId;
        private final long startedNanos;
        private boolean immediate;
        private AnonymizationResult result;
        private boolean ignored;
        private FailureReason reason;
        private String failureClass = "unknown";

        private Tracked(UUID requestId, long startedNanos) {
            this.requestId = requestId;
            this.startedNanos = startedNanos;
        }

        @Override
        public void succeeded(AnonymizationResult anonymization) {
            this.result = anonymization;
            if (immediate) {
                afterCommit();
                afterCompletion(STATUS_COMMITTED);
            }
        }

        @Override
        public void repeated() {
            ignored = true;
        }

        @Override
        public void refused() {
            ignored = true;
        }

        @Override
        public void failed(FailureReason failureReason, Throwable cause) {
            this.reason = failureReason;
            this.failureClass = cause == null ? "unknown" : cause.getClass().getName();
            if (immediate) {
                afterCompletion(STATUS_ROLLED_BACK);
            }
        }

        @Override
        public void afterCommit() {
            if (result == null) {
                return;
            }
            anonymized.increment();
            log.info(
                    "Account anonymized: requestId={}, botsStopped={}, sessionsRevoked={}, feedbackDeleted={}",
                    requestId,
                    result.botsStopped(),
                    result.sessionsRevoked(),
                    result.feedbackDeleted());
        }

        @Override
        public void afterCompletion(int status) {
            if (ignored && result == null && reason == null) {
                return;
            }
            duration.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
            if (status == STATUS_COMMITTED) {
                return;
            }
            FailureReason counted = reason == null ? FailureReason.ROLLED_BACK : reason;
            failures.get(counted).increment();
            log.error(
                    "Account anonymization failed: requestId={}, reason={}, exception={}",
                    requestId,
                    counted,
                    failureClass);
        }
    }
}
