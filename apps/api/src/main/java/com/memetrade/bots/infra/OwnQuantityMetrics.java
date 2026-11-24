package com.memetrade.bots.infra;

import com.memetrade.bots.domain.SellCapOutcome;
import com.memetrade.bots.worker.OwnHoldingsStreaks;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U4 (NFR2.10, NFR2.11, NFR2.16, NFR2.18; observability-design.md "Contadores"): the meters of
 * the own quantity, in the worker only (the api has none of them), all registered in the constructor so every series
 * exists with 0 from the start:
 *
 * <ul>
 *   <li>{@code real_own_holdings_reads_total}: one per read of the own holdings, counted before the call whatever its
 *       answer;
 *   <li>{@code real_own_holdings_unavailable_total{reason}}: BUDGET_EXCEEDED, READ_FAILED, BOT_NOT_OWNED, when the read
 *       that counted in the reads answers unavailable (so the ratio of the alarm never passes 1);
 *   <li>{@code real_cycle_sell_capped_total{outcome}}: CAPPED, NO_OWN_QUANTITY, STALE_SNAPSHOT (Prometheus only);
 *   <li>{@code real_own_holdings_stuck_bots}: the bots with 3 or more unavailable reads in a row (decision F3).
 * </ul>
 *
 * Labels are enums only: never a tenant, a person, a bot, a mint nor a wallet.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class OwnQuantityMetrics {

    private final Counter reads;
    private final Map<UnavailableReason, Counter> unavailable = new EnumMap<>(UnavailableReason.class);
    private final Map<SellCapOutcome, Counter> capped = new EnumMap<>(SellCapOutcome.class);

    public OwnQuantityMetrics(MeterRegistry meters, OwnHoldingsStreaks streaks) {
        reads = Counter.builder("real_own_holdings_reads")
                .description("Reads of the own holdings of a REAL bot by its cycle (U4)")
                .register(meters);
        for (UnavailableReason reason : UnavailableReason.values()) {
            unavailable.put(
                    reason,
                    Counter.builder("real_own_holdings_unavailable")
                            .description("Reads of the own holdings that answered unavailable, by reason (U4)")
                            .tag("reason", reason.name())
                            .register(meters));
        }
        for (SellCapOutcome outcome : SellCapOutcome.values()) {
            capped.put(
                    outcome,
                    Counter.builder("real_cycle_sell_capped")
                            .description("Sales of the REAL cycle cut by the own quantity of the bot, by outcome (U4)")
                            .tag("outcome", outcome.name())
                            .register(meters));
        }
        Gauge.builder("real_own_holdings_stuck_bots", streaks, OwnHoldingsStreaks::stuckBots)
                .description("REAL bots with 3 or more unavailable reads of their own holdings in a row (U4, F3)")
                .register(meters);
    }

    /** One read of the own holdings, counted before the call. */
    public void read() {
        reads.increment();
    }

    /** The read counted in {@link #read} answered unavailable for {@code reason}. */
    public void unavailable(UnavailableReason reason) {
        unavailable.get(reason).increment();
    }

    /** The cut of a sale ended with {@code outcome}. */
    public void capped(SellCapOutcome outcome) {
        capped.get(outcome).increment();
    }
}
