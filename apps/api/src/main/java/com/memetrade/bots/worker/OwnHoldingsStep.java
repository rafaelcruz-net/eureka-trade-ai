package com.memetrade.bots.worker;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.OwnQuantitySell;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.OwnQuantityMetrics;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U4 (F1 steps 0 to 4; BR2.2, BR2.3, BR2.5, BR3.1; NFR1.4, NFR1.10, NFR2.3, NFR2.16): the step of
 * the REAL cycle BEFORE its transaction. Without a lock and in autocommit, it checks whether the cycle will run, in
 * order, and stops at the first check that fails:
 *
 * <ol>
 *   <li>P0 (no query): the market REAL, the flag on, the slot not older than 5 minutes;
 *   <li>P1: the row of the bot, ACTIVE, without a pause reason, of the market REAL; the SAME row gives the mark M0
 *       (alignment A9), read before the snapshot of the own holdings opens (BR3.1);
 *   <li>P2: the slot not claimed yet;
 *   <li>P3: the system pause off.
 * </ol>
 *
 * Then it counts the read in {@code real_own_holdings_reads_total}, calls the read of C15 with the budget of 250 ms, keeps
 * the sequence of unavailable reads of the bot (F3) and, when the read is unavailable, counts its reason and writes one
 * log line (ERROR for BOT_NOT_OWNED, a defect: the dispatcher only hands bots of their owner). The checks that matter
 * are done again under the lock by the cycle; these only spare a read the cycle would not use.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class OwnHoldingsStep {

    /** BR2.2: the budget of the read of the own holdings. */
    static final Duration BUDGET = Duration.ofMillis(250);

    private static final Duration SLOT = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(OwnHoldingsStep.class);

    private final JdbcBotRepository bots;
    private final RealBotHoldings holdings;
    private final OwnQuantitySell flag;
    private final OwnQuantityMetrics metrics;
    private final OwnHoldingsStreaks streaks;
    private final Clock clock;

    public OwnHoldingsStep(
            JdbcBotRepository bots,
            RealBotHoldings holdings,
            OwnQuantitySell flag,
            OwnQuantityMetrics metrics,
            OwnHoldingsStreaks streaks,
            Clock clock) {
        this.bots = bots;
        this.holdings = holdings;
        this.flag = flag;
        this.metrics = metrics;
        this.streaks = streaks;
        this.clock = clock;
    }

    /**
     * The snapshot for the cycle of {@code bot} in the slot {@code slotStart}.
     *
     * @param market the market of the dispatch; empty for the callers of {@code run(BotRef, Instant)}, then P1 decides
     */
    public HoldingsSnapshot read(BotRef bot, Optional<BotMarket> market, Instant slotStart) {
        if (market.isPresent() && market.get() != BotMarket.REAL) {
            return new HoldingsSnapshot.Skipped("P0_MARKET");
        }
        Progress progress = new Progress();
        try {
            return checkAndRead(bot, slotStart, progress);
        } catch (RuntimeException failure) {
            return failed(bot, progress, failure);
        }
    }

    private HoldingsSnapshot checkAndRead(BotRef bot, Instant slotStart, Progress progress) {
        if (!flag.enabled()) {
            return new HoldingsSnapshot.Skipped("P0_FLAG");
        }
        if (clock.instant().isAfter(slotStart.plus(SLOT))) {
            return new HoldingsSnapshot.Skipped("P0_SLOT_EXPIRED");
        }
        Optional<JdbcBotRepository.PrecheckRow> row = bots.precheckRow(bot);
        if (row.isEmpty()
                || row.get().market() != BotMarket.REAL
                || row.get().state() != BotState.ACTIVE
                || !row.get().reasons().isEmpty()) {
            return new HoldingsSnapshot.Skipped("P1_BOT");
        }
        long m0 = row.get().changeSeq();
        progress.m0 = m0;
        if (bots.slotClaimed(bot.botId(), slotStart)) {
            return new HoldingsSnapshot.Skipped("P2_SLOT_CLAIMED");
        }
        if (bots.systemPaused()) {
            return new HoldingsSnapshot.Skipped("P3_SYSTEM_PAUSE");
        }
        metrics.read();
        progress.counted = true;
        long started = System.nanoTime();
        HoldingsResult result =
                holdings.holdings(bot.scope().tenantId(), bot.scope().ownerUserId(), bot.botId(), BUDGET);
        progress.result = result;
        streaks.update(bot.botId(), result);
        if (result instanceof HoldingsResult.Unavailable unavailable) {
            metrics.unavailable(unavailable.reason());
            logUnavailable(bot, unavailable.reason(), Duration.ofNanos(System.nanoTime() - started));
        }
        return new HoldingsSnapshot.Read(m0, result);
    }

    /**
     * Review R-02 (BR2.6): a failure of the step never reaches the cycle, so it never marks the slot FAILED nor loses
     * the buys. After a read, only the bookkeeping failed and the read is kept. Before it (P0 to P3, or a read that broke
     * its contract and threw), it is an unavailable read: counted as a read with the reason READ_FAILED, the streak goes
     * up, one WARN line; the cut then sells nothing of the wallet ({@code Read} with READ_FAILED when M0 was read,
     * {@code Skipped}, that the cycle turns into NOT_READ under the lock, when it was not).
     */
    private HoldingsSnapshot failed(BotRef bot, Progress progress, RuntimeException failure) {
        if (progress.result != null && progress.m0 != null) {
            log.warn(
                    "event=REAL_OWN_HOLDINGS_STEP_FAILED botId={} phase=AFTER_READ exceptionClass={}",
                    bot.botId(),
                    failure.getClass().getSimpleName());
            return new HoldingsSnapshot.Read(progress.m0, progress.result);
        }
        HoldingsResult.Unavailable unavailable = new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED);
        if (!progress.counted) {
            quietly(metrics::read);
        }
        quietly(() -> metrics.unavailable(UnavailableReason.READ_FAILED));
        quietly(() -> streaks.update(bot.botId(), unavailable));
        log.warn(
                "event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={} phase=PRE exceptionClass={}",
                bot.botId(),
                UnavailableReason.READ_FAILED,
                failure.getClass().getSimpleName());
        return progress.m0 == null
                ? new HoldingsSnapshot.Skipped("PRE_PHASE_FAILED")
                : new HoldingsSnapshot.Read(progress.m0, unavailable);
    }

    /** The bookkeeping of a failure never fails the cycle; its own failure is logged and dropped. */
    private void quietly(Runnable bookkeeping) {
        try {
            bookkeeping.run();
        } catch (RuntimeException failure) {
            log.warn(
                    "event=REAL_OWN_HOLDINGS_STEP_FAILED phase=BOOKKEEPING exceptionClass={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** How far the step went before a failure. */
    private static final class Progress {
        private Long m0;
        private boolean counted;
        private HoldingsResult result;
    }

    private void logUnavailable(BotRef bot, UnavailableReason reason, Duration elapsed) {
        if (reason == UnavailableReason.BOT_NOT_OWNED) {
            log.error("event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={}", bot.botId(), reason);
            return;
        }
        log.warn(
                "event=REAL_OWN_HOLDINGS_UNAVAILABLE botId={} reason={} elapsedMs={} streak={}",
                bot.botId(),
                reason,
                elapsed.toMillis(),
                streaks.streak(bot.botId()));
    }
}
