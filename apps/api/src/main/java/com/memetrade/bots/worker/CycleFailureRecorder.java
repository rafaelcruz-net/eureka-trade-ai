package com.memetrade.bots.worker;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR3.5, NFR10.2: records the FAILED slot AFTER the cycle transaction rolled back, in a NEW transaction that is never
 * nested in the cycle's (the cycle connection was already returned and its RUNNING row is gone, so nothing waits on an
 * uncommitted row and no cycle ever holds two connections). The insert never overwrites a recorded slot. It adds one
 * to {@code bots.cycle.errors{cause}} (NFR9.2) and logs ERROR with the cause only (NFR9.5).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class CycleFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(CycleFailureRecorder.class);

    private final JdbcCycleRepository cycles;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Map<CycleCause, Counter> errors = new EnumMap<>(CycleCause.class);

    public CycleFailureRecorder(
            JdbcCycleRepository cycles, Clock clock, PlatformTransactionManager manager, MeterRegistry meters) {
        this.cycles = cycles;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (CycleCause cause : CycleCause.values()) {
            errors.put(
                    cause,
                    Counter.builder("bots.cycle.errors")
                            .description("Cycles of the bots that failed and were rolled back (NFR9.2)")
                            .tag("cause", cause.name())
                            .register(meters));
        }
    }

    public void record(BotRef bot, Instant slotStart, UUID cycleId, CycleCause cause) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> cycles.recordFailed(cycleId, bot, slotStart, cause.name(), now));
        errors.get(cause).increment();
        log.error(
                "Bot cycle failed: cycleId={}, botId={}, slotStart={}, cause={}",
                cycleId,
                bot.botId(),
                slotStart,
                cause);
    }
}
