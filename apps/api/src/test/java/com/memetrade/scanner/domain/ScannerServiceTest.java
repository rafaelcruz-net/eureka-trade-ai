package com.memetrade.scanner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketListingQuery.Filters;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.scanner.FakeListing;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** F3 in the domain (U1 behaviour kept; U4 sources, photo, cursor and risk added). */
@Tag("u1")
class ScannerServiceTest {

    private final MutableClock clock = new MutableClock();
    private final FakeListing listing = new FakeListing();
    private final List<TokenId> assessed = new ArrayList<>();
    private final ScannerService service = new ScannerService(
            listing,
            token -> {
                assessed.add(token);
                return new AssessmentResult.Unavailable(UnavailableReason.INSUFFICIENT_COVERAGE);
            },
            clock);

    @Test
    void theAgeIsWholeMinutesSinceCreationWithTheSourceAndFreshnessOfTheObservation() {
        listing.add(
                "m1", clock.instant(), Optional.of(clock.instant().minus(Duration.ofSeconds(150))), FakeListing.none());

        MarketValue.Known age = (MarketValue.Known) first().ageMinutes();

        assertThat(age.value()).isEqualByComparingTo("2");
        assertThat(age.source()).isEqualTo(FakeListing.SOURCE);
    }

    @Test
    void anUnknownCreationKeepsTheAgeUnknownNeverZero() {
        listing.add("m1", clock.instant(), Optional.empty(), FakeListing.none());

        assertThat(first().ageMinutes()).isInstanceOf(MarketValue.Unknown.class);
    }

    @Test
    void coverageCountsTheKnownFieldsOutOfFiveAndAnInformedZeroStaysKnown() {
        listing.add("m1", clock.instant(), Optional.of(clock.instant()), FakeListing.none());

        ScannerRow row = first();
        assertThat(row.coverageInformed()).isEqualTo(4);
        assertThat(row.coverageTotal()).isEqualTo(5);
        assertThat(((MarketValue.Known) row.liquidity().value()).value()).isEqualByComparingTo("0");
    }

    @Test
    void theFirstPageFixesThePhotoAndTheNextPagesReuseItFromTheCursor() {
        for (int i = 0; i < 3; i++) {
            listing.add("m" + i, clock.instant(), Optional.of(clock.instant().minusSeconds(i)), FakeListing.none());
        }
        Instant photo = clock.instant();
        ScannerPage first = service.list(query(Optional.empty(), 2));
        clock.advance(Duration.ofSeconds(30));
        ScannerPage second = service.list(query(first.nextCursor(), 2));

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).extracting(ScannerRow::mint).containsExactly("m2");
        assertThat(second.nextCursor()).isEmpty();
        assertThat(listing.requests).extracting(r -> r.snapshotAt()).containsExactly(photo, photo);
    }

    @Test
    void aStoredAssessmentIsShownAndAMissingOneIsAskedToC12() {
        listing.add("m1", clock.instant(), Optional.empty(), FakeListing.available(25, "LOW"));
        listing.add("m2", clock.instant(), Optional.empty(), FakeListing.none());

        ScannerPage page = service.list(query(Optional.empty(), 10));

        assertThat(page.items().get(0).risk()).isEqualTo(new AssessmentResult.Available(25, RiskBand.LOW, 1));
        assertThat(page.items().get(1).risk()).isInstanceOf(AssessmentResult.Unavailable.class);
        assertThat(assessed).containsExactly(new TokenId("solana", "m2"));
    }

    @Test
    void whileSomeSourceIsPendingAndNoneIsAvailableTheListIsEmptyWithTheSources() {
        listing.sources(State.PENDING, State.FAILED);
        listing.add("m1", clock.instant(), Optional.empty(), FakeListing.none());

        ScannerPage page = service.list(query(Optional.empty(), 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.sources()).hasSize(2);
        assertThat(listing.requests).isEmpty();
    }

    @Test
    void withEverySourceFailedTheListIsSourceUnavailableWithTheirState() {
        listing.sources(State.FAILED, State.FAILED);

        assertThatThrownBy(() -> service.list(query(Optional.empty(), 10)))
                .isInstanceOfSatisfying(SourcesUnavailableException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.SOURCE_UNAVAILABLE);
                    assertThat(failure.sources()).hasSize(2);
                });
    }

    @Test
    @Tag("u4")
    void oneFailedSourceKeepsTheListOfTheOther() {
        listing.sources(State.FAILED, State.DEGRADED);
        listing.add("m1", clock.instant(), Optional.empty(), FakeListing.none());

        assertThat(service.list(query(Optional.empty(), 10)).items()).hasSize(1);
    }

    @Test
    @Tag("u4")
    void aCursorOfOtherFiltersOrAnotherOrderIsRefused() {
        for (int i = 0; i < 3; i++) {
            listing.add("m" + i, clock.instant(), Optional.empty(), FakeListing.none());
        }
        String cursor = service.list(query(Optional.empty(), 1)).nextCursor().orElseThrow();
        ScannerQuery otherOrder =
                new ScannerQuery(Filters.none(), SortField.AGE, SortOrder.DESC, Optional.of(cursor), 1);
        ScannerQuery otherFilters = new ScannerQuery(
                new Filters(
                        Optional.empty(),
                        Optional.of(5L),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                SortField.AGE,
                SortOrder.ASC,
                Optional.of(cursor),
                1);

        for (ScannerQuery other : List.of(otherOrder, otherFilters)) {
            assertThatThrownBy(() -> service.list(other))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(
                                    refused.fields().getFirst().field())
                            .isEqualTo("cursor"));
        }
    }

    @Test
    @Tag("u4")
    void theCountersComeFromTheWholePhoto() {
        listing.add("m1", clock.instant(), Optional.empty(), FakeListing.none());
        listing.excluded = 7;
        listing.pending = 3;

        ScannerPage page = service.list(query(Optional.empty(), 10));

        assertThat(page.excludedByUnknown()).isEqualTo(7);
        assertThat(page.pendingNewTokens()).isEqualTo(3);
        assertThat(page.total()).isEqualTo(1);
    }

    private ScannerRow first() {
        return service.list(query(Optional.empty(), 50)).items().getFirst();
    }

    private static ScannerQuery query(Optional<String> cursor, int limit) {
        return new ScannerQuery(Filters.none(), SortField.AGE, SortOrder.ASC, cursor, limit);
    }
}
