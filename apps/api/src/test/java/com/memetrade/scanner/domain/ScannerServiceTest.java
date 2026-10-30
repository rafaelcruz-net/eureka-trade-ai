package com.memetrade.scanner.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class ScannerServiceTest {

    private static final SourceRef SOURCE = new SourceRef("simulated", "Fonte simulada", true);

    private final MutableClock clock = new MutableClock();
    private final FakeListing listing = new FakeListing();
    private final ScannerService service = new ScannerService(listing, clock);

    @Test
    void theAgeIsWholeMinutesSinceCreationWithTheSourceAndFreshnessOfTheObservation() {
        listing.add(token("A", clock.instant().minus(Duration.ofMinutes(17)).minusSeconds(30), true));

        ScannerRow row = service.list(Optional.empty(), 50).items().getFirst();

        MarketValue.Known age = (MarketValue.Known) row.ageMinutes();
        assertThat(age.value()).isEqualByComparingTo("17");
        assertThat(age.source()).isEqualTo(SOURCE);
    }

    @Test
    void anUnknownCreationKeepsTheAgeUnknownNeverZero() {
        listing.add(token("A", null, true));

        ScannerRow row = service.list(Optional.empty(), 50).items().getFirst();

        assertThat(row.ageMinutes()).isInstanceOf(MarketValue.Unknown.class);
        assertThat(row.coverageInformed()).isEqualTo(3);
    }

    @Test
    void coverageCountsTheKnownFieldsOutOfFive() {
        listing.add(token("A", clock.instant().minusSeconds(60), true));
        listing.add(token("B", clock.instant().minusSeconds(60), false));

        List<ScannerRow> rows = service.list(Optional.empty(), 50).items();

        assertThat(rows).extracting(ScannerRow::coverageInformed).containsExactly(4, 3);
        assertThat(rows).extracting(ScannerRow::coverageTotal).containsOnly(5);
    }

    @Test
    void theFirstPageCountsTotalAndNextPagesReuseItAndTheCutoff() {
        for (int i = 0; i < 3; i++) {
            listing.add(token("T" + i, clock.instant().minusSeconds(60L * (i + 1)), true));
        }

        ScannerPage first = service.list(Optional.empty(), 2);
        listing.count = 999;
        clock.advance(Duration.ofMinutes(1));
        ScannerPage second = service.list(first.nextCursor(), 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(second.total()).isEqualTo(3);
        assertThat(listing.lastCutoff).isEqualTo(MutableClock.DEFAULT_START);
        assertThat(second.nextCursor()).isEmpty();
    }

    @Test
    void theLastPageHasNoNextCursorAndU1CountersAreZero() {
        listing.add(token("A", clock.instant(), true));

        ScannerPage page = service.list(Optional.empty(), 50);

        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.excludedByUnknown()).isZero();
        assertThat(page.pendingNewTokens()).isZero();
        assertThat(page.sources()).hasSize(1);
    }

    @Test
    void anInformedZeroStaysAKnownZero() {
        listing.add(token("A", clock.instant(), true));

        MarketValue liquidity =
                service.list(Optional.empty(), 50).items().getFirst().liquidity();

        assertThat(((MarketValue.Known) liquidity).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aBlankCursorMeansTheFirstPage() {
        listing.add(token("A", clock.instant(), true));

        assertThat(service.list(Optional.of(" "), 50).total()).isEqualTo(1);
    }

    private MarketListingQuery.ListedToken token(String mint, Instant created, boolean holdersKnown) {
        Map<MarketField, MarketValue> values = new EnumMap<>(MarketField.class);
        Instant observed = clock.instant();
        values.put(MarketField.PRICE, new MarketValue.Known(new BigDecimal("0.001"), SOURCE, observed, false));
        values.put(MarketField.LIQUIDITY, new MarketValue.Known(BigDecimal.ZERO, SOURCE, observed, false));
        values.put(MarketField.VOLUME_24H, new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)));
        values.put(
                MarketField.HOLDERS,
                holdersKnown
                        ? new MarketValue.Known(BigDecimal.TEN, SOURCE, observed, false)
                        : new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)));
        values.put(
                MarketField.CREATED_AT,
                created == null
                        ? new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE))
                        : new MarketValue.Known(BigDecimal.valueOf(created.getEpochSecond()), SOURCE, observed, false));
        return new MarketListingQuery.ListedToken(
                new TokenId("solana", mint),
                "SYM",
                null,
                new MarketListingQuery.ListingPosition(created == null ? 1 : 0, 0, mint, "solana"),
                values);
    }

    /** Returns tokens in insertion order; ordering itself is covered against PostgreSQL in ScannerIT. */
    private static final class FakeListing implements MarketListingQuery {
        private final List<ListedToken> tokens = new ArrayList<>();
        long count = -1;
        Instant lastCutoff;

        void add(ListedToken token) {
            tokens.add(token);
        }

        @Override
        public ListingPage page(Optional<ListingPosition> after, Instant cutoff, int limit) {
            lastCutoff = cutoff;
            int start = after.map(p -> indexOf(p) + 1).orElse(0);
            int end = Math.min(tokens.size(), start + limit);
            return new ListingPage(List.copyOf(tokens.subList(start, end)), end < tokens.size());
        }

        @Override
        public long count(Instant cutoff) {
            return count >= 0 ? count : tokens.size();
        }

        @Override
        public List<SourceStatus> sources() {
            return List.of(new SourceStatus(SOURCE, SourceStatus.State.OK, Optional.of(Instant.EPOCH)));
        }

        private int indexOf(ListingPosition position) {
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).position().equals(position)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
