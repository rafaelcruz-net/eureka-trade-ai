package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.BotStateMachine;
import com.memetrade.botwallet.api.RealChain;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

/**
 * Step 20 of the U4 (loop-back 1 of the Build and Test), written BEFORE the code: the rhythm of the REAL orders against
 * the limit of NFR1.9 of the U1, at most {@value #WALLET_LIMIT_PER_MINUTE} new signing requests per minute per bot
 * wallet in the TxSigner ({@code WalletRequestLimiter}, apps/txsigner; U1 review-03, R-16).
 *
 * <p>The U4 asks the TxSigner for one signature per order (the repetitions carry the same signingRequestId and are
 * answered from the record, never counted again). The orders of one wallet come from two places only:
 *
 * <ul>
 *   <li>the bot cycles: one cycle per bot per 5-minute slot ({@code bot_cycle} unique per bot and slot), one decision per
 *       cycle, at most {@link BotStateMachine#ACTIVE_LIMIT} active bots per person, and one order in progress per bot
 *       ({@code uq_real_order_bot_in_progress});
 *   <li>the liquidation ("vender tudo e sacar"): one sale at a time, created only when the person has no order in
 *       PROPOSED, QUOTED, SIGNED or SENT; while it runs the decisions of the bots are discarded.
 * </ul>
 *
 * The fastest liquidation is therefore paced by the confirmation round: a new sale exists only after the previous one
 * left SENT. These tests drive the real jobs of the worker at the configured cadence, with a network that finalizes
 * every transaction at once, and count every call to the TxSigner (repetitions included, which is stricter than the
 * limiter).
 */
@Tag("u4-real-execution")
class RealOrderRateIT extends AbstractRealExecutionIT {

    /** NFR1.9 of the U1: {@code WalletRequestLimiter.REQUESTS_PER_MINUTE} in apps/txsigner. */
    private static final int WALLET_LIMIT_PER_MINUTE = 60;

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration TICK = Duration.ofMillis(200);
    private static final int POSITIONS = 40;

    @Autowired
    private RealExecutionConfig.CheckedSettings settings;

    @Autowired
    private LiquidationService liquidationService;

    @Autowired
    private Environment environment;

    // ----- the refusal of the limit (R-16): FAILED SIGNING_REFUSED, one ask, no transaction, the reason recorded -----

    @Test
    void aWalletRateLimitedRefusalFailsTheOrderOnceWithoutATransactionAndRecordsTheReason() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        signer.swapRefusal = "WALLET_RATE_LIMITED";

        driver.runOnce();
        // The order is final: the next rounds of the worker never take it again (a final row cannot even be leased).
        driver.runOnce();
        confirmation.runOnce();
        ambiguous.runOnce();

        assertThat(state(order)).isEqualTo("FAILED");
        assertThat(failureCode(order)).isEqualTo("SIGNING_REFUSED");
        assertThat(signer.swapCalls.get()).as("asked once, never again").isEqualTo(1);
        assertThat(signer.swapSignatures.get()).isZero();
        assertThat(chain.totalSends()).as("no transaction at all").isZero();
        assertThat(entries(order)).containsEntry("RESERVE_RELEASE", 50_000_000L);
        assertThat(jdbc.sql("""
                        select payload -> 'details' ->> 'signerRefusal' from audit_outbox
                        where payload ->> 'action' = 'REAL_ORDER_STATE_CHANGED'
                          and payload -> 'details' ->> 'to' = 'FAILED' and payload::text like :target""")
                        .param("target", "%real-order:" + order + "%")
                        .query(String.class)
                        .list())
                .as("the reason of the TxSigner is recorded with the failure")
                .containsExactly("WALLET_RATE_LIMITED");
    }

    @Test
    void aRefusalCodeOutsideTheFormatIsRecordedAsUnknown() {
        assertThat(OrderDriver.refusalCode("WALLET_RATE_LIMITED")).isEqualTo("WALLET_RATE_LIMITED");
        assertThat(OrderDriver.refusalCode(null)).isEqualTo("UNKNOWN");
        assertThat(OrderDriver.refusalCode("wallet rate limited")).isEqualTo("UNKNOWN");
        assertThat(OrderDriver.refusalCode("A".repeat(65))).isEqualTo("UNKNOWN");
    }

    // ----- the rhythm in normal operation -----

    @Test
    void theFastestLiquidationStaysUnderTheLimitOfTheWallet() {
        RealExecutionProperties.Jobs jobs = settings.properties().jobs();
        Setup setup = realBot("AUTO_FULL");
        List<String> mints = new ArrayList<>();
        for (int i = 0; i < POSITIONS; i++) {
            String mint = token("0.0001", 6, 0);
            mints.add(mint);
            position(wallet(setup), mint, 1_000L + i, 100_000L);
        }
        List<Instant> asks = new CopyOnWriteArrayList<>();
        signer.whileSigning = () -> asks.add(clock.instant());
        liquidationService.start(new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER));

        Set<String> settled = new HashSet<>();
        Duration elapsed = Duration.ZERO;
        Duration horizon = jobs.confirmationInterval().multipliedBy(POSITIONS + 10L);
        while (elapsed.compareTo(horizon) <= 0) {
            if (due(elapsed, Duration.ofSeconds(10))) {
                fresh(mints);
            }
            // The order that sells fastest: the sale, its send, the network, its confirmation, all in one tick.
            if (due(elapsed, jobs.liquidationInterval())) {
                liquidations.runOnce();
            }
            if (due(elapsed, jobs.driverInterval())) {
                driver.runOnce();
            }
            finalizeSent(setup, settled);
            if (due(elapsed, jobs.confirmationInterval())) {
                confirmation.runOnce();
            }
            clock.advance(TICK);
            elapsed = elapsed.plus(TICK);
        }

        assertThat(asks)
                .as("every position was sold, so the window was really full")
                .hasSize(POSITIONS);
        int busiest = busiestWindow(asks);
        long pacedByConfirmation = WINDOW.dividedBy(jobs.confirmationInterval()) + 1;
        assertThat(busiest)
                .as("asks of one wallet in the busiest minute")
                .isGreaterThanOrEqualTo((int) pacedByConfirmation - 1)
                .isLessThanOrEqualTo((int) pacedByConfirmation)
                .isLessThanOrEqualTo(WALLET_LIMIT_PER_MINUTE);
    }

    @Test
    void theBotsAndTheLiquidationTogetherStayUnderTheLimit() {
        RealExecutionProperties.Jobs jobs = settings.properties().jobs();
        CronExpression slots = CronExpression.parse(environment.getRequiredProperty("memetrade.bots.slot-cron"));
        Duration shortestSlot = shortestGap(slots);
        long slotsPerWindow = ceil(WINDOW, shortestSlot);
        long botOrders = (long) BotStateMachine.ACTIVE_LIMIT * slotsPerWindow;
        long liquidationSales = WINDOW.dividedBy(jobs.confirmationInterval()) + 1;
        // A sale needs one liquidation round as well: the liquidation alone can never beat one sale per round.
        long liquidationRounds = WINDOW.dividedBy(jobs.liquidationInterval()) + 1;

        assertThat(shortestSlot).as("one cycle per bot per slot").isEqualTo(Duration.ofMinutes(5));
        assertThat(botOrders).isEqualTo(3);
        assertThat(liquidationSales).isLessThanOrEqualTo(liquidationRounds);
        // Even counted together (the liquidation discards the decisions and waits for the orders in flight), the
        // bound of normal operation keeps a margin under the limit of the wallet.
        assertThat(botOrders + liquidationSales).isEqualTo(34).isLessThan(WALLET_LIMIT_PER_MINUTE);
    }

    // ----- helpers -----

    private static boolean due(Duration elapsed, Duration interval) {
        assertThat(interval.toMillis() % TICK.toMillis())
                .as("the cadence %s is a multiple of the tick", interval)
                .isZero();
        return elapsed.toMillis() % interval.toMillis() == 0;
    }

    /** The prices stay fresh for the barrier, as the collectors keep them in operation. */
    private void fresh(List<String> mints) {
        solAt("150.00", 0);
        for (String mint : mints) {
            observe(mint, "0.0001", 6, 0);
        }
    }

    /** A network that answers at once: every SENT sale of the person is finalized with the effect of what it offered. */
    private void finalizeSent(Setup setup, Set<String> settled) {
        jdbc.sql("""
                        select tx_signature, input_base_units from real_order
                        where user_id = :user and state = 'SENT'""")
                .param("user", setup.userId())
                .query((rs, row) -> {
                    String signature = rs.getString("tx_signature");
                    if (settled.add(signature)) {
                        chain.effect(
                                signature,
                                new RealChain.SwapEffect(40_000L, -rs.getLong("input_base_units"), 5_000L, true));
                        chain.finalize(signature);
                    }
                    return signature;
                })
                .list();
    }

    /** The most asks inside any window of one minute, as the sliding window of the limiter counts them. */
    private static int busiestWindow(List<Instant> asks) {
        List<Instant> sorted = asks.stream().sorted().toList();
        int busiest = 0;
        int start = 0;
        for (int end = 0; end < sorted.size(); end++) {
            while (!sorted.get(start).isAfter(sorted.get(end).minus(WINDOW))) {
                start++;
            }
            busiest = Math.max(busiest, end - start + 1);
        }
        return busiest;
    }

    private static Duration shortestGap(CronExpression cron) {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
        Duration shortest = null;
        LocalDateTime previous = cron.next(at);
        for (int i = 0; i < 300; i++) {
            LocalDateTime next = cron.next(previous);
            Duration gap = Duration.between(previous, next);
            shortest = shortest == null || gap.compareTo(shortest) < 0 ? gap : shortest;
            previous = next;
        }
        return shortest;
    }

    private static long ceil(Duration window, Duration slot) {
        return (window.toMillis() + slot.toMillis() - 1) / slot.toMillis();
    }
}
