package com.memetrade.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery.Filters;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.MarketListingQuery.ListingRequest;
import com.memetrade.marketdata.api.MarketListingQuery.ListingResult;
import com.memetrade.marketdata.api.MarketListingQuery.Position;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.JdbcMarketListingQuery;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The single list query against PostgreSQL (Step 11, BR2.1 to BR2.8). Every request filters on this test's own source
 * ({@code sourceId}), so totals and counters only see this test's tokens in the shared database.
 */
@Tag("u4")
class ScannerListQueryIT extends AbstractPostgresIT {

    @Autowired
    private JdbcMarketListingQuery listing;

    @Autowired
    private MarketStore store;

    private SourceRef a;
    private SourceRef b;

    @BeforeEach
    void ownSources() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = source("la-" + suffix);
        b = source("lb-" + suffix);
    }

    @Test
    void everyMinimumIsInclusiveAndTheSmallestStepBeyondIsOut() {
        String exact = token(a, "1000", "5000", "200", Duration.ofMinutes(10), 39);

        assertThat(mints(filters("1000", null, null, null, null))).contains(exact);
        assertThat(mints(filters("1000.0001", null, null, null, null))).doesNotContain(exact);
        assertThat(mints(filters(null, "5000", null, null, null))).contains(exact);
        assertThat(mints(filters(null, "5000.01", null, null, null))).doesNotContain(exact);
        assertThat(mints(filters(null, null, "200", null, null))).contains(exact);
        assertThat(mints(filters(null, null, "201", null, null))).doesNotContain(exact);
        assertThat(mints(filters(null, null, null, null, 39))).contains(exact);
        assertThat(mints(filters(null, null, null, null, 38))).doesNotContain(exact);
    }

    @Test
    void theAgeIsComparedWithTheExactDuration() {
        String fifteen = token(a, "1", "1", "1", Duration.ofMinutes(15), null);
        String fifteenAndASecond =
                token(a, "1", "1", "1", Duration.ofMinutes(15).plusSeconds(1), null);

        assertThat(mints(filters(null, null, null, 15L, null)))
                .contains(fifteen)
                .doesNotContain(fifteenAndASecond);
    }

    @Test
    void filtersCombineAndAnUnknownFieldIsOutAndCountedOnce() {
        String passes = token(a, "2000", "2000", "500", Duration.ofMinutes(5), 20);
        String unknownHolders = token(a, "2000", "2000", null, null, null);
        String knownFailure = token(a, "10", "2000", null, Duration.ofMinutes(5), 20);

        ListingResult result = listing.list(request(filters("1000", null, "100", 30L, 50), Optional.empty(), 10));

        assertThat(mints(result)).containsExactly(passes);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.excludedByUnknown())
                .as("counted once although holders, age and risk are unknown")
                .isEqualTo(1);
        assertThat(mints(result)).doesNotContain(unknownHolders, knownFailure);
    }

    @Test
    void anUnavailableIndicatorIsUnknownForTheFilterAndGoesLastInTheRiskOrder() {
        String low = token(a, "1", "1", "1", null, 10);
        String high = token(a, "1", "1", "1", null, 80);
        String unavailable = token(a, "1", "1", "1", null, null);
        unavailable(unavailable);

        ListingResult filtered = listing.list(request(filters(null, null, null, null, 100), Optional.empty(), 10));
        List<String> ascending = mints(listing.list(new ListingRequest(
                clock.instant(),
                filters(null, null, null, null, null),
                SortField.RISK_SCORE,
                SortOrder.ASC,
                Optional.empty(),
                10)));
        List<String> descending = mints(listing.list(new ListingRequest(
                clock.instant(),
                filters(null, null, null, null, null),
                SortField.RISK_SCORE,
                SortOrder.DESC,
                Optional.empty(),
                10)));

        assertThat(mints(filtered)).containsExactlyInAnyOrder(low, high);
        assertThat(filtered.excludedByUnknown()).isEqualTo(1);
        assertThat(ascending).containsExactly(low, high, unavailable);
        assertThat(descending).containsExactly(high, low, unavailable);
    }

    @Test
    void theAgeOrderListsTheYoungestFirstUnknownLastAndBreaksTiesByMint() {
        String older = token(a, "1", "1", "1", Duration.ofMinutes(30), null);
        String tieZ = tokenWithMint(a, "zz" + SyntheticIds.mint().substring(2), Duration.ofMinutes(10));
        String tieA = tokenWithMint(a, "AA" + SyntheticIds.mint().substring(2), Duration.ofMinutes(10));
        String unknown = token(a, "1", "1", "1", null, null);

        assertThat(mints(listing.list(request(filters(null, null, null, null, null), Optional.empty(), 10))))
                .containsExactly(tieA, tieZ, older, unknown);
    }

    @Test
    void pagesAreStableWithoutDuplicatesOrGapsInBothDirections() {
        for (int i = 0; i < 7; i++) {
            token(a, Integer.toString(100 + i % 3), "1", "1", i == 3 ? null : Duration.ofMinutes(i + 1), null);
        }
        token(a, null, "1", "1", null, null);
        for (SortOrder order : SortOrder.values()) {
            List<String> big = walk(SortField.LIQUIDITY, order, 100);
            List<String> small = walk(SortField.LIQUIDITY, order, 2);

            assertThat(small)
                    .containsExactlyElementsOf(big)
                    .doesNotHaveDuplicates()
                    .hasSize(8);
        }
        assertThat(walk(SortField.AGE, SortOrder.ASC, 3))
                .containsExactlyElementsOf(walk(SortField.AGE, SortOrder.ASC, 50));
    }

    @Test
    void tokensFirstSeenAfterThePhotoWaitAsPendingNewTokens() {
        token(a, "1", "1", "1", null, null);
        Instant photo = clock.instant();
        clock.advance(Duration.ofSeconds(5));
        String late = token(a, "1", "1", "1", null, null);

        ListingResult result = listing.list(new ListingRequest(
                photo, filters(null, null, null, null, null), SortField.AGE, SortOrder.ASC, Optional.empty(), 10));

        assertThat(mints(result)).doesNotContain(late);
        assertThat(result.pendingNewTokens()).isEqualTo(1);
    }

    @Test
    void aFailedSourceKeepsTheTokensOfTheOtherWithTheOtherValues() {
        String shared = token(a, "100", "1", "1", null, null);
        observe(b, shared, MarketField.LIQUIDITY, "999", Duration.ofSeconds(1));
        String onlyB = token(b, "5", "1", "1", null, null);
        fail(a);

        List<ListedToken> listed = listing.list(new ListingRequest(
                        clock.instant(),
                        new Filters(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(b.sourceId())),
                        SortField.AGE,
                        SortOrder.ASC,
                        Optional.empty(),
                        10))
                .tokens();

        assertThat(listed).extracting(t -> t.token().mint()).containsExactlyInAnyOrder(shared, onlyB);
        ListedToken sharedRow = listed.stream()
                .filter(t -> t.token().mint().equals(shared))
                .findFirst()
                .orElseThrow();
        assertThat(((MarketValue.Known)
                                sharedRow.fields().get(MarketField.LIQUIDITY).value())
                        .value())
                .isEqualByComparingTo("999");
        assertThat(mints(filters(null, null, null, null, null)))
                .as("source A failed")
                .isEmpty();
    }

    @Test
    void aTokenWithoutAnObservationInTheLast24HoursIsNotListed() {
        String old = token(a, "1", "1", "1", null, null);
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        observe(a, token(a, "1", "1", "1", null, null), MarketField.PRICE, "1", Duration.ZERO);

        assertThat(mints(filters(null, null, null, null, null)))
                .doesNotContain(old)
                .hasSize(1);
    }

    @Test
    void thePlanNeverReadsTheHistoryTable() {
        String plan = listing.explain(request(filters("1", "1", "1", 60L, 50), Optional.empty(), 50));

        assertThat(plan).doesNotContain("observation_sample");
    }

    private List<String> walk(SortField sort, SortOrder order, int limit) {
        List<String> all = new ArrayList<>();
        Optional<Position> after = Optional.empty();
        Instant photo = clock.instant();
        while (true) {
            ListingResult page = listing.list(
                    new ListingRequest(photo, filters(null, null, null, null, null), sort, order, after, limit));
            page.tokens().forEach(t -> all.add(t.token().mint()));
            if (!page.hasMore()) {
                return all;
            }
            after = Optional.of(page.tokens().getLast().position());
        }
    }

    private List<String> mints(Filters filters) {
        return mints(listing.list(request(filters, Optional.empty(), 100)));
    }

    private static List<String> mints(ListingResult result) {
        return result.tokens().stream().map(t -> t.token().mint()).toList();
    }

    private ListingRequest request(Filters filters, Optional<Position> after, int limit) {
        return new ListingRequest(clock.instant(), filters, SortField.AGE, SortOrder.ASC, after, limit);
    }

    /** Filters always restricted to this test's source A. */
    private Filters filters(String minLiquidity, String minVolume, String minHolders, Long maxAge, Integer maxRisk) {
        return new Filters(
                Optional.ofNullable(minLiquidity).map(BigDecimal::new),
                Optional.ofNullable(maxAge),
                Optional.ofNullable(minVolume).map(BigDecimal::new),
                Optional.ofNullable(minHolders).map(BigDecimal::new),
                Optional.ofNullable(maxRisk),
                Optional.of(a.sourceId()));
    }

    private SourceRef source(String id) {
        SourceRef ref = new SourceRef(id, "List " + id, true);
        store.registerSource(ref, Duration.ofSeconds(30));
        store.updateSourceStatus(id, State.OK, Optional.empty(), clock.instant(), Optional.of(clock.instant()));
        return ref;
    }

    private void fail(SourceRef source) {
        store.updateSourceStatus(source.sourceId(), State.FAILED, Optional.empty(), clock.instant(), Optional.empty());
    }

    private String tokenWithMint(SourceRef source, String mint, Duration age) {
        return seed(source, mint, "1", "1", "1", age, null);
    }

    private String token(
            SourceRef source, String liquidity, String volume, String holders, Duration age, Integer risk) {
        return seed(source, SyntheticIds.mint(), liquidity, volume, holders, age, risk);
    }

    private String seed(
            SourceRef source,
            String mint,
            String liquidity,
            String volume,
            String holders,
            Duration age,
            Integer risk) {
        TokenId token = new TokenId("solana", mint);
        store.upsertToken(new TokenInfo(token, "LST", null, 6), clock.instant());
        observe(source, mint, MarketField.PRICE, "1", Duration.ZERO);
        observeOrUnknown(source, mint, MarketField.LIQUIDITY, liquidity);
        observeOrUnknown(source, mint, MarketField.VOLUME_24H, volume);
        observeOrUnknown(source, mint, MarketField.HOLDERS, holders);
        if (age != null) {
            observe(
                    source,
                    mint,
                    MarketField.CREATED_AT,
                    Long.toString(clock.instant().minus(age).getEpochSecond()),
                    Duration.ZERO);
        }
        if (risk != null) {
            jdbc.sql("""
                            insert into risk_assessment (chain, mint, assessment_id, weight_set_version, status, score,
                              band, coverage, decision_hint, factors, inputs_fingerprint, computed_at)
                            values ('solana', :mint, :id, 1, 'AVAILABLE', :score, :band, 100, 'ELIGIBLE', '[]'::jsonb,
                              'fp', now())
                            """)
                    .param("mint", mint)
                    .param("id", UUID.randomUUID())
                    .param("score", risk)
                    .param("band", risk <= 39 ? "LOW" : risk <= 69 ? "MEDIUM" : "HIGH")
                    .update();
        }
        return mint;
    }

    private void unavailable(String mint) {
        jdbc.sql("""
                        insert into risk_assessment (chain, mint, assessment_id, weight_set_version, status,
                          unavailable_reason, coverage, decision_hint, factors, inputs_fingerprint, computed_at)
                        values ('solana', :mint, :id, 1, 'UNAVAILABLE', 'INSUFFICIENT_COVERAGE', 50, 'NO_TRADE',
                          '[]'::jsonb, 'fp', now())
                        """).param("mint", mint).param("id", UUID.randomUUID()).update();
    }

    private void observeOrUnknown(SourceRef source, String mint, MarketField field, String value) {
        if (value == null) {
            store.upsertObservation(
                    new TokenId("solana", mint),
                    source.sourceId(),
                    field,
                    MarketValue.Unknown.notProvided(source),
                    clock.instant());
        } else {
            observe(source, mint, field, value, Duration.ZERO);
        }
    }

    private void observe(SourceRef source, String mint, MarketField field, String value, Duration ago) {
        Instant at = clock.instant().minus(ago);
        store.upsertObservation(
                new TokenId("solana", mint),
                source.sourceId(),
                field,
                new MarketValue.Known(new BigDecimal(value), source, at, false),
                at);
    }
}
