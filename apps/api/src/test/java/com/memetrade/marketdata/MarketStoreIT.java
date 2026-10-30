package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MarketStoreIT extends AbstractPostgresIT {

    @Autowired
    private MarketCollector collector;

    @Autowired
    private MarketStore store;

    @Test
    void aCollectionWritesUnknownNotProvidedAndAKnownZeroAndMarksTheSourceOk() {
        assertThat(collector.collect()).isTrue();

        assertThat(jdbc.sql("select count(*) from market_observation where field = 'HOLDERS' and status = 'UNKNOWN'"
                                + " and unknown_reason = 'NOT_PROVIDED' and value is null")
                        .query(Integer.class)
                        .single())
                .isPositive();
        assertThat(jdbc.sql("select count(*) from market_observation where field = 'LIQUIDITY' and status = 'KNOWN'"
                                + " and value = 0")
                        .query(Integer.class)
                        .single())
                .isPositive();
        Map<String, Object> source = jdbc.sql("select status, simulated from data_source where source_id = 'simulated'")
                .query()
                .singleRow();
        assertThat(source).containsEntry("status", "OK").containsEntry("simulated", true);
    }

    @Test
    void aFailedCollectionKeepsTheLastSuccessfulTimeAndTheObservations() {
        collector.collect();
        Instant lastOk = jdbc.sql("select last_updated_at from data_source where source_id = 'simulated'")
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        int observations = jdbc.sql("select count(*) from market_observation")
                .query(Integer.class)
                .single();
        clock.advance(Duration.ofSeconds(15));

        store.sourceFailed(new SourceRef("simulated", "Fonte simulada", true), clock.instant());

        assertThat(jdbc.sql("select status from data_source where source_id = 'simulated'")
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
        assertThat(jdbc.sql("select last_updated_at from data_source where source_id = 'simulated'")
                        .query(java.sql.Timestamp.class)
                        .single()
                        .toInstant())
                .isEqualTo(lastOk);
        assertThat(jdbc.sql("select count(*) from market_observation")
                        .query(Integer.class)
                        .single())
                .isEqualTo(observations);
    }

    @Test
    void tokensWithoutCreationTimeAreInTheUnknownGroup() {
        collector.collect();

        List<Integer> groups = jdbc.sql("select distinct sort_group from token where chain = 'solana' order by 1")
                .query(Integer.class)
                .list();
        assertThat(groups).containsExactly(0, 1);
    }

    @Test
    void theAdapterIsTheSimulatedOne() {
        MarketSourceAdapter adapter = collector.adapter();
        assertThat(adapter.source().simulated()).isTrue();
    }
}
