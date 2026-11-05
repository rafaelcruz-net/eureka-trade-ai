package com.memetrade.bots.worker;

import com.memetrade.shared.config.ProcessRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of the worker (C20, infrastructure-specification.md "Trabalhos por processo"): the slot dispatch at
 * {@code slot-cron} (each 5-minute UTC slot plus 60 s), followed by the closing check; the UTC midnight; the daily
 * decision purge. Exists only in the worker with {@code memetrade.bots.scheduler.enabled} (the worker profile); the api
 * never runs a bot job. Each job is also guarded by its own ShedLock.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.bots.scheduler.enabled", havingValue = "true")
public class BotSchedules {

    private final SlotDispatcher dispatcher;
    private final ClosingCheckJob closingCheck;
    private final MidnightJob midnight;
    private final DecisionPurgeJob purge;

    public BotSchedules(
            SlotDispatcher dispatcher, ClosingCheckJob closingCheck, MidnightJob midnight, DecisionPurgeJob purge) {
        this.dispatcher = dispatcher;
        this.closingCheck = closingCheck;
        this.midnight = midnight;
        this.purge = purge;
    }

    @Scheduled(cron = "${memetrade.bots.slot-cron}", zone = "UTC")
    public void slot() {
        dispatcher.runSlot();
        closingCheck.runOnce();
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void midnight() {
        midnight.runOnce();
    }

    @Scheduled(cron = "${memetrade.bots.decision-purge-cron}", zone = "UTC")
    public void purge() {
        purge.runOnce();
    }
}
