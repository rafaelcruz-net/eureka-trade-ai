package com.memetrade.bots;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractU5WorkerIT;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U4, Etapa 16 (after the layer; NFR2.10, NFR2.16, NFR2.18): in the WORKER, the 7 series of the
 * three counters and the gauge of the stuck bots are registered with 0 from the start, in the registry the actuator
 * {@code /actuator/prometheus} of the worker exports (each counter with the suffix {@code _total}). The api has none of
 * them (checked by {@code BotListOwnQuantityFieldIT}, in the context of the api).
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityMetricsIT extends AbstractU5WorkerIT {

    @Autowired
    private MeterRegistry meters;

    @Test
    void theWorkerRegistersTheSevenSeriesAndTheGaugeWithZero() {
        List<Meter> own = meters.getMeters().stream()
                .filter(meter -> List.of(
                                "real_own_holdings_reads",
                                "real_own_holdings_unavailable",
                                "real_cycle_sell_capped",
                                "real_own_holdings_stuck_bots")
                        .contains(meter.getId().getName()))
                .toList();

        assertThat(own).hasSize(8);
        assertThat(meters.get("real_own_holdings_unavailable").counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .containsExactlyInAnyOrder("BUDGET_EXCEEDED", "READ_FAILED", "BOT_NOT_OWNED");
        assertThat(meters.get("real_cycle_sell_capped").counters())
                .extracting(counter -> counter.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("CAPPED", "NO_OWN_QUANTITY", "STALE_SNAPSHOT");
        assertThat(meters.get("real_own_holdings_stuck_bots").gauge().value()).isZero();
    }
}
