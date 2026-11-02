package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The configured simulated sources collected into PostgreSQL (U1 behaviour, U4 sources sim-a and sim-b). */
class MarketStoreIT extends AbstractPostgresIT {

    @Autowired
    private MarketCollectorJob job;

    @Test
    void aCollectionWritesUnknownNotProvidedAndAKnownZeroAndMarksTheSourceCollected() {
        assertThat(job.collectNow("sim-a")).isPresent();

        assertThat(jdbc.sql("select count(*) from market_observation where source_id = 'sim-a' and field = 'HOLDERS'"
                                + " and status = 'UNKNOWN' and unknown_reason = 'NOT_PROVIDED' and value is null")
                        .query(Integer.class)
                        .single())
                .isPositive();
        assertThat(jdbc.sql("select count(*) from market_observation where source_id = 'sim-a' and field = 'LIQUIDITY'"
                                + " and status = 'KNOWN' and value = 0")
                        .query(Integer.class)
                        .single())
                .isPositive();
        Map<String, Object> source = jdbc.sql("select status, simulated from data_source where source_id = 'sim-a'")
                .query()
                .singleRow();
        assertThat(source.get("status")).isIn("OK", "DEGRADED");
        assertThat(source).containsEntry("simulated", true);
    }

    @Test
    void tokensWithoutCreationTimeAreInTheUnknownGroup() {
        job.collectNow("sim-a");

        List<Integer> groups = jdbc.sql("select distinct sort_group from token where chain = 'solana' order by 1")
                .query(Integer.class)
                .list();
        assertThat(groups).containsExactly(0, 1);
    }

    @Test
    void theConfiguredSourcesAreTheSimulatedOnes() {
        assertThat(job.sourceIds()).containsExactly("sim-a", "sim-b");
        assertThat(jdbc.sql("select bool_and(simulated) from data_source where source_id in ('sim-a', 'sim-b')")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
