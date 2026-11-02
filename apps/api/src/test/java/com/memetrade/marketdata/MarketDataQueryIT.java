package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketRiskInputs.TokenInputs;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.FieldResolver;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** C11 and the risk inputs against PostgreSQL (Step 6): resolution, freshness, 1-hour window and failures. */
@Tag("u4")
class MarketDataQueryIT extends AbstractPostgresIT {

    @Autowired
    private JdbcMarketDataQuery query;

    @Autowired
    private MarketStore store;

    @Autowired
    private FieldResolver resolver;

    private SourceRef a;
    private SourceRef b;
    private TokenId token;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = source("qa-" + suffix);
        b = source("qb-" + suffix);
        token = new TokenId("solana", SyntheticIds.mint());
        store.upsertToken(new TokenInfo(token, "QRY", null, 6), clock.instant());
    }

    @Test
    void theReferencePriceIsTheMostRecentKnownValueWithStaleComputedAtReading() {
        observe(a, MarketField.PRICE, "1.0", Duration.ofMinutes(2));
        observe(b, MarketField.PRICE, "1.02", Duration.ofMinutes(1));

        MarketValue.Known price = (MarketValue.Known) query.referencePrice(token);
        clock.advance(Duration.ofMinutes(4).plusSeconds(1));

        assertThat(price.value()).isEqualByComparingTo("1.02");
        assertThat(price.source()).isEqualTo(b);
        assertThat(((MarketValue.Known) query.referencePrice(token)).stale()).isTrue();
    }

    @Test
    void aFailedSourceIsLeftOutAndOnlyFailedSourcesGiveSourceUnavailable() {
        observe(a, MarketField.LIQUIDITY, "500", Duration.ofMinutes(3));
        observe(b, MarketField.LIQUIDITY, "999", Duration.ofMinutes(1));
        fail(b);

        assertThat(((MarketValue.Known) query.liquidity(token)).value()).isEqualByComparingTo("500");
        fail(a);
        assertThat(query.liquidity(token))
                .isEqualTo(new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty()));
        assertThat(query.inputsFor(token).orElseThrow().allSourcesFailed()).isTrue();
    }

    @Test
    void aTokenWithoutObservationsIsNotYetObservedAndHasNoRiskInputs() {
        assertThat(query.referencePrice(token))
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()));
        assertThat(query.inputsFor(token)).isEmpty();
    }

    @Test
    void thePreviousPriceIsASampleFromSixtyToSixtyFiveMinutesAgoBothEndsIncluded() {
        observe(a, MarketField.PRICE, "2", Duration.ZERO);
        sample(a, "1.5", Duration.ofMinutes(65));
        assertThat(previous()).isEqualByComparingTo("1.5");

        sample(a, "1.6", Duration.ofMinutes(60));
        assertThat(previous()).as("the most recent in the window").isEqualByComparingTo("1.6");
    }

    @Test
    void samplesJustOutsideTheWindowAreIgnored() {
        observe(a, MarketField.PRICE, "2", Duration.ZERO);
        sample(a, "1.1", Duration.ofMinutes(65).plusSeconds(1));
        sample(a, "1.2", Duration.ofMinutes(59).plusSeconds(59));

        assertThat(query.inputsFor(token).orElseThrow().priceOneHourAgo())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()));
    }

    @Test
    void aSampleOfAFailedSourceIsNotUsedAsThePreviousPrice() {
        observe(a, MarketField.PRICE, "2", Duration.ZERO);
        sample(b, "1.3", Duration.ofMinutes(62));
        fail(b);

        assertThat(query.inputsFor(token).orElseThrow().priceOneHourAgo()).isInstanceOf(MarketValue.Unknown.class);
    }

    @Test
    void theWindowHasOnePointPerFiveMinuteBucketAndGapsAreUnknown() {
        sample(a, "1.0", Duration.ofMinutes(10));

        PriceVolumeWindow window = query.window(token, Duration.ofMinutes(30));

        assertThat(window.points()).hasSize(7);
        assertThat(window.points().get(4).price()).isInstanceOf(MarketValue.Known.class);
        assertThat(window.points().get(0).price()).isInstanceOf(MarketValue.Unknown.class);
        assertThat(window.points().get(4).volume24h()).isInstanceOf(MarketValue.Unknown.class);
    }

    @Test
    void theRecentTokensIncludeThisOneWithItsResolvedFields() {
        observe(a, MarketField.HOLDERS, "300", Duration.ofMinutes(1));

        TokenInputs inputs = query.recentTokens().stream()
                .filter(t -> t.token().equals(token))
                .findFirst()
                .orElseThrow();

        assertThat(((MarketValue.Known) inputs.value(MarketField.HOLDERS)).value())
                .isEqualByComparingTo("300");
        assertThat(inputs.value(MarketField.VOLUME_24H)).isInstanceOf(MarketValue.Unknown.class);
        assertThat(inputs.allSourcesFailed()).isFalse();
    }

    @Test
    void aTechnicalFailureBecomesUnknownSourceUnavailable() {
        JdbcMarketDataQuery broken = new JdbcMarketDataQuery(
                JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "y")),
                resolver,
                store,
                clock);

        assertThat(broken.referencePrice(token))
                .isEqualTo(new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty()));
        assertThat(broken.window(token, Duration.ofHours(1)).points()).isEmpty();
    }

    @Test
    void theGlobalMarkIsTheStoredMark() {
        long before = query.globalMark();
        store.incrementStatusMark();

        assertThat(query.globalMark()).isEqualTo(before + 1);
    }

    private SourceRef source(String id) {
        SourceRef ref = new SourceRef(id, "Query " + id, true);
        store.registerSource(ref, Duration.ofSeconds(30));
        store.updateSourceStatus(id, State.OK, Optional.empty(), clock.instant(), Optional.of(clock.instant()));
        return ref;
    }

    private void fail(SourceRef source) {
        store.updateSourceStatus(source.sourceId(), State.FAILED, Optional.empty(), clock.instant(), Optional.empty());
    }

    private void observe(SourceRef source, MarketField field, String value, Duration ago) {
        Instant at = clock.instant().minus(ago);
        store.upsertObservation(
                token, source.sourceId(), field, new MarketValue.Known(new BigDecimal(value), source, at, false), at);
    }

    private void sample(SourceRef source, String value, Duration ago) {
        Instant at = clock.instant().minus(ago);
        store.insertSample(
                token,
                source.sourceId(),
                MarketField.PRICE,
                new MarketValue.Known(new BigDecimal(value), source, at, false),
                at);
    }

    private BigDecimal previous() {
        return ((MarketValue.Known) query.inputsFor(token).orElseThrow().priceOneHourAgo()).value();
    }
}
