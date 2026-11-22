package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (NFR4.6; observability-design.md "Contador 2"): {@code pnl.mode_block.unavailable}, one
 * series per market, registered with zero at start-up. Labels carry only the market, never a person, bot nor
 * correlation id. Counting an event is not arithmetic of PAPER or REAL values (NEVER_SUM_MODES).
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PnlModeMetrics {

    public static final String UNAVAILABLE = "pnl.mode_block.unavailable";

    private final Map<PnlMarket, Counter> unavailable = new EnumMap<>(PnlMarket.class);

    public PnlModeMetrics(MeterRegistry meters) {
        for (PnlMarket market : PnlMarket.values()) {
            unavailable.put(
                    market,
                    Counter.builder(UNAVAILABLE)
                            .description("Blocks of GET /api/v1/pnl/by-mode answered UNAVAILABLE, by market (NFR4.6)")
                            .tag("market", market.name())
                            .register(meters));
        }
    }

    /** One block answered UNAVAILABLE. */
    public void unavailable(PnlMarket market) {
        unavailable.get(market).increment();
    }
}
