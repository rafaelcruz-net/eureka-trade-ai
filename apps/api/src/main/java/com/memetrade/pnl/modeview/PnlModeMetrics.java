package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (NFR4.6; observability-design.md "Contador 2"): {@code pnl.mode_block.unavailable}, the
 * blocks of {@code GET /api/v1/pnl/by-mode} answered UNAVAILABLE.
 *
 * <p>261121-paper-real-unico U2 (NFR1.6, R-03): labelled by {@code market} and {@code cause}, 2 x 5 = 10 series, all
 * registered with zero at start-up. {@code TIMEOUT} counts a deadline passed in ANY part of the block (the queue, the
 * opening of the transaction, the attribution, the stream of the ledger, the proration, the prices, the
 * reconciliation; {@code 57014} or the deadline at a row): it is the gate of 0.5% of the scenario {@code pnl-by-mode}.
 * {@code REJECTED} is a full queue; {@code ERROR} any other technical failure. Labels carry only these closed values,
 * never a person, bot nor correlation id. Counting an event is not arithmetic of PAPER or REAL values (NEVER_SUM_MODES).
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PnlModeMetrics {

    public static final String UNAVAILABLE = "pnl.mode_block.unavailable";

    /** Why a block was UNAVAILABLE. */
    public enum Cause {
        TIMEOUT,
        REJECTED,
        ERROR,
        RECONCILIATION_MISMATCH,
        TOO_MANY_ORDERS
    }

    private final Map<PnlMarket, Map<Cause, Counter>> unavailable = new EnumMap<>(PnlMarket.class);

    public PnlModeMetrics(MeterRegistry meters) {
        for (PnlMarket market : PnlMarket.values()) {
            Map<Cause, Counter> causes = new EnumMap<>(Cause.class);
            for (Cause cause : Cause.values()) {
                causes.put(
                        cause,
                        Counter.builder(UNAVAILABLE)
                                .description("Blocks of GET /api/v1/pnl/by-mode answered UNAVAILABLE, by market and"
                                        + " cause (NFR4.6, NFR1.6)")
                                .tag("market", market.name())
                                .tag("cause", cause.name())
                                .register(meters));
            }
            unavailable.put(market, causes);
        }
    }

    /** One block answered UNAVAILABLE. */
    public void unavailable(PnlMarket market, Cause cause) {
        unavailable.get(market).get(cause).increment();
    }
}
