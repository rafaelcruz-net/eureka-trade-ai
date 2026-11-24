package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U3 (NFR3.11, NFR3.15; observability-design.md): the two counters of the comparison C6.
 *
 * <ul>
 *   <li>{@code pnl.compare.template_mismatches}: the 422 {@code TEMPLATE_MISMATCH}, counted after the ownership of the
 *       two bots (1 series);
 *   <li>{@code pnl.compare.side_unavailable{market,cause}}: a side answered UNAVAILABLE, 2 x 5 = 10 series with the
 *       closed causes of {@link PnlModeMetrics.Cause}; {@link #sideUnavailable} is the {@link UnavailableSink} of C6.
 * </ul>
 *
 * All series are registered with 0 at start-up; the labels carry only closed values. Counting is not arithmetic of a
 * value of either mode (NEVER_SUM_MODES).
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class PnlCompareMetrics {

    public static final String TEMPLATE_MISMATCHES = "pnl.compare.template_mismatches";
    public static final String SIDE_UNAVAILABLE = "pnl.compare.side_unavailable";

    private final Counter templateMismatches;
    private final Map<PnlMarket, Map<PnlModeMetrics.Cause, Counter>> sides = new EnumMap<>(PnlMarket.class);

    public PnlCompareMetrics(MeterRegistry meters) {
        this.templateMismatches = Counter.builder(TEMPLATE_MISMATCHES)
                .description("Comparisons of C6 refused with 422 TEMPLATE_MISMATCH (NFR3.11)")
                .register(meters);
        for (PnlMarket market : PnlMarket.values()) {
            Map<PnlModeMetrics.Cause, Counter> causes = new EnumMap<>(PnlModeMetrics.Cause.class);
            for (PnlModeMetrics.Cause cause : PnlModeMetrics.Cause.values()) {
                causes.put(
                        cause,
                        Counter.builder(SIDE_UNAVAILABLE)
                                .description("Sides of GET /api/v1/pnl/compare answered UNAVAILABLE, by market and"
                                        + " cause (NFR3.11)")
                                .tag("market", market.name())
                                .tag("cause", cause.name())
                                .register(meters));
            }
            sides.put(market, causes);
        }
    }

    /** One 422 {@code TEMPLATE_MISMATCH}. */
    public void templateMismatch() {
        templateMismatches.increment();
    }

    /** One side of C6 answered UNAVAILABLE. */
    public void sideUnavailable(PnlMarket market, PnlModeMetrics.Cause cause) {
        sides.get(market).get(cause).increment();
    }
}
