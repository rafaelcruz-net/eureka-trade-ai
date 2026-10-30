package com.memetrade.marketdata.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.memetrade.marketdata.domain.InMemoryMarketStore;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class MarketCollectorJobTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryMarketStore store = new InMemoryMarketStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MarketCollectorJob job = new MarketCollectorJob(
            new MarketCollector(new SimulatedMarketSource(42, 10, clock), store, clock), store, clock, registry);

    @Test
    void beforeTheFirstCollectionTheSourceReportsFailedAndNoAge() {
        assertThat(registry.get("market_source_status").gauge().value()).isEqualTo(1.0);
        assertThat(job.collectionAge()).isEmpty();
    }

    @Test
    void afterACollectionTheGaugesShowOkAndTheAge() {
        job.run();
        clock.advance(Duration.ofSeconds(20));

        assertThat(registry.get("market_source_status").gauge().value()).isZero();
        assertThat(registry.get("market_source_last_collection_age_seconds")
                        .gauge()
                        .value())
                .isEqualTo(20.0);
        assertThat(store.tokens).hasSize(10);
    }

    @Test
    void theCycleNeverThrows() {
        assertThatCode(job::cycle).doesNotThrowAnyException();
    }
}
