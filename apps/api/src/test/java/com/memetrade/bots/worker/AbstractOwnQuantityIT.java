package com.memetrade.bots.worker;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.botwallet.api.RealChain;
import com.memetrade.pnl.RealPnlSeed;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.application.RealBotPnl;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 261121-paper-real-unico U4 (test support of the Etapas 4 and 5): two REAL bots A and B of one person in the same bot
 * wallet, both holding the synthetic mint {@code XYZ} of 6 decimals (E1: A bought 100 XYZ for 100,000,000 lamports, B
 * bought 300 XYZ for 450,000,000), and the REAL cycle of the worker with the flag of the U4 on or off.
 */
abstract class AbstractOwnQuantityIT extends AbstractRealExecutionIT {

    static final long A_UNITS = 100_000_000L;
    static final long A_COST = 100_000_000L;
    static final long B_UNITS = 300_000_000L;
    static final long B_COST = 450_000_000L;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected RealBotPnl realBotPnl;

    /** One person, bots A (the bot of the setup) and B, both holding XYZ in the same bot wallet. */
    record Pair(Setup setup, UUID a, UUID b, String xyz, RealPnlSeed seed) {
        UUID tenantId() {
            return setup.tenantId();
        }

        UUID userId() {
            return setup.userId();
        }

        BotRef refA() {
            return new BotRef(a, new TenantScope(setup.tenantId(), setup.userId()));
        }

        BotRef refB() {
            return new BotRef(b, new TenantScope(setup.tenantId(), setup.userId()));
        }
    }

    /** E1: A 100 XYZ for 100,000,000; B 300 XYZ for 450,000,000 (fees of 5,000 each). */
    protected Pair pair() {
        return pair(true, true);
    }

    /** A pair where only the bots asked hold XYZ. */
    protected Pair pair(boolean aBuys, boolean bBuys) {
        Setup setup = realBot("AUTO_FULL");
        UUID b = realBotRow(setup.tenantId(), setup.userId());
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                setup.tenantId(),
                setup.userId(),
                setup.botWalletId(),
                clock.instant().minusSeconds(7_200),
                100);
        if (aBuys) {
            seed.buy(setup.botId(), setup.mint(), A_UNITS, A_COST, 5_000);
        }
        if (bBuys) {
            seed.buy(b, setup.mint(), B_UNITS, B_COST, 5_000);
        }
        return new Pair(setup, setup.botId(), b, setup.mint(), seed);
    }

    /** The slot of the clock now (a cycle older than 5 minutes is skipped). */
    protected Instant slot() {
        return SlotDispatcher.slotOf(clock.instant());
    }

    /** The next slot, with the clock moved to 60 s after its start (how the dispatcher opens it). */
    protected Instant nextSlot() {
        Instant next = slot().plus(Duration.ofMinutes(5));
        clock.set(next.plusSeconds(60));
        return next;
    }

    protected OwnQuantityCycle cycle(boolean flagOn) {
        return new OwnQuantityCycle(context, flagOn);
    }

    /** The SELL decisions of the bot recorded in the queue (any state). */
    protected List<Long> sellDecisions(UUID botId) {
        return jdbc.sql("select token_base_units from real_decision_request where bot_id = :b and side = 'SELL'"
                        + " order by created_at")
                .param("b", botId)
                .query(Long.class)
                .list();
    }

    protected String outcome(CycleResult result) {
        return jdbc.sql("select coalesce(outcome, skip_reason) from bot_cycle where cycle_id = :c")
                .param("c", result.cycleId().orElseThrow())
                .query(String.class)
                .single();
    }

    /** The recorded context of the decision of the cycle (text of the jsonb). */
    protected String context(CycleResult result) {
        return jdbc.sql("""
                        select d.context::text from decision d join bot_cycle c on c.decision_id = d.decision_id
                        where c.cycle_id = :c""")
                .param("c", result.cycleId().orElseThrow())
                .query(String.class)
                .single();
    }

    /**
     * Takes the PENDING decision of the bot through the queue, the driver and the confirmation: the sale receives
     * {@code receivedLamports} and pays {@code feeLamports} from the bot wallet, in the slot {@code slot}.
     */
    protected UUID settleSale(UUID decisionRequestId, long units, long receivedLamports, long feeLamports, long slot) {
        consumer.runOnce();
        UUID order = orderOf(decisionRequestId);
        driver.runOnce();
        String signature = txSignature(order);
        chain.effect(
                signature,
                new RealChain.SwapEffect(
                        receivedLamports - feeLamports, -units, feeLamports, true, OptionalLong.of(slot)));
        chain.finalize(signature);
        confirmation.runOnce();
        return order;
    }

    protected UUID pendingSellOf(UUID botId) {
        return jdbc.sql("select decision_request_id from real_decision_request where bot_id = :b and side = 'SELL'"
                        + " and state = 'PENDING'")
                .param("b", botId)
                .query(UUID.class)
                .single();
    }

    protected RealBotPnlLine line(Pair pair, UUID botId) {
        return realBotPnl
                .botPnl(pair.tenantId(), pair.userId(), botId, PnlWindow.all())
                .orElseThrow();
    }

    /** BR7.2: the bots plus "Sem bot" match the REAL ledger exactly (the reconciliation of the pass). */
    protected ReconciliationResult.Status reconciliation(Pair pair) {
        return realBotPnl
                .realPnl(pair.tenantId(), pair.userId())
                .reconciliation()
                .status();
    }
}
