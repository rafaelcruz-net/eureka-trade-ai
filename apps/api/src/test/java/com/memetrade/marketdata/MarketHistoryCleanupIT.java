package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.marketdata.infra.MarketHistoryCleanup;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** F6 (Step 7): 24 hours exactly stays, 24 hours and 1 second goes; a repetition changes nothing. */
@Tag("u4")
class MarketHistoryCleanupIT extends AbstractPostgresIT {

    @Autowired
    private MarketHistoryCleanup cleanup;

    @Autowired
    private MarketStore store;

    @Autowired
    private JdbcMarketDataQuery query;

    @Autowired
    private LockProvider lockProvider;

    private SourceRef source;
    private TokenId token;

    @BeforeEach
    void seed() {
        source = new SourceRef("hc-" + UUID.randomUUID().toString().substring(0, 8), "Cleanup", true);
        store.registerSource(source, Duration.ofSeconds(30));
        token = new TokenId("solana", SyntheticIds.mint());
        store.upsertToken(new TokenInfo(token, "OLD", null, 6), clock.instant().minus(Duration.ofDays(2)));
    }

    @Test
    void exactlyTwentyFourHoursStaysAndOneSecondMoreIsDeleted() {
        sample(Duration.ofHours(24));
        sample(Duration.ofHours(24).plusSeconds(1));
        sample(Duration.ofHours(1));

        assertThat(cleanup.runLocked())
                .hasValueSatisfying(deleted -> assertThat(deleted).isPositive());

        assertThat(remaining()).isEqualTo(2);
    }

    @Test
    void runningItAgainDeletesNothingMore() {
        sample(Duration.ofHours(30));
        cleanup.runLocked();

        assertThat(cleanup.runLocked()).contains(0L);
        assertThat(remaining()).isZero();
    }

    @Test
    void anotherRunHoldingTheLockSkipsThisOne() {
        sample(Duration.ofHours(30));
        var held = lockProvider
                .lock(new LockConfiguration(
                        clock.instant(), "market-history-cleanup", Duration.ofMinutes(1), Duration.ZERO))
                .orElseThrow();
        try {
            assertThat(cleanup.runLocked()).isEmpty();
            assertThat(remaining()).isEqualTo(1);
        } finally {
            held.unlock();
        }
    }

    @Test
    void theTokenRowStaysButATokenWithoutARecentObservationIsNoLongerAssessedOrListed() {
        Instant old = clock.instant().minus(Duration.ofHours(25));
        store.upsertObservation(
                token,
                source.sourceId(),
                MarketField.PRICE,
                new MarketValue.Known(BigDecimal.ONE, source, old, false),
                old);
        sample(Duration.ofHours(25));

        cleanup.runLocked();

        assertThat(jdbc.sql("select count(*) from token where mint = :m")
                        .param("m", token.mint())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(query.inputsFor(token)).isEmpty();
    }

    private void sample(Duration ago) {
        Instant at = clock.instant().minus(ago);
        store.insertSample(
                token,
                source.sourceId(),
                MarketField.PRICE,
                new MarketValue.Known(BigDecimal.ONE, source, at, false),
                at);
    }

    private int remaining() {
        return jdbc.sql("select count(*) from observation_sample where mint = :m")
                .param("m", token.mint())
                .query(Integer.class)
                .single();
    }
}
