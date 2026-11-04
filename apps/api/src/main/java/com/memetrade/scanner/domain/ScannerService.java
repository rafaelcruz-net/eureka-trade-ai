package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketListingQuery.ListedRisk;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.MarketListingQuery.ListingRequest;
import com.memetrade.marketdata.api.MarketListingQuery.ListingResult;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F3, the scanner list (US2.2, US2.3, US3.2): market data and assessments are global, so no customer data is read.
 *
 * <ul>
 *   <li>Without any source OK or DEGRADED: while some source is PENDING the answer is an empty page with the state of
 *       the sources ("waiting for the first collection"); with every source FAILED it is
 *       {@code 503 SOURCE_UNAVAILABLE} with that state (BR1.4).
 *   <li>The first page fixes {@code snapshotAt}; the next pages reuse it from the cursor, which must belong to the same
 *       filters and order (BR2.6, BR2.7). The values of the rows are always the current ones.
 *   <li>A token without a stored assessment yet gets it computed once through C12; until then it counts as UNKNOWN
 *       for the risk filter and order (BR2.3).
 * </ul>
 */
public class ScannerService {

    public static final int COVERAGE_TOTAL = 5;

    private final MarketListingQuery market;
    private final RiskAssessmentQuery risk;
    private final Clock clock;

    public ScannerService(MarketListingQuery market, RiskAssessmentQuery risk, Clock clock) {
        this.market = market;
        this.risk = risk;
        this.clock = clock;
    }

    public ScannerPage list(ScannerQuery query) {
        Optional<ScannerCursor> cursor =
                query.cursor().map(ScannerCursor::decode).map(c -> c.requireFor(query));
        List<SourceStatus> sources = market.sources();
        if (sources.stream().noneMatch(s -> s.status().isAvailable())) {
            if (!sources.isEmpty() && sources.stream().allMatch(s -> s.status() == SourceStatus.State.FAILED)) {
                throw new SourcesUnavailableException(sources);
            }
            return new ScannerPage(List.of(), Optional.empty(), 0, sources, 0, 0);
        }
        Instant snapshotAt = cursor.map(ScannerCursor::snapshotAt).orElseGet(clock::instant);
        ListingResult result = market.list(new ListingRequest(
                snapshotAt,
                query.filters(),
                query.sort(),
                query.order(),
                cursor.map(ScannerCursor::last),
                query.limit()));
        Instant now = clock.instant();
        List<ScannerRow> rows =
                result.tokens().stream().map(token -> row(token, now)).toList();
        long shownBefore = cursor.map(ScannerCursor::offsetShown).orElse(0L);
        Optional<String> next = result.hasMore()
                ? Optional.of(new ScannerCursor(
                                snapshotAt,
                                query.filtersDigest(),
                                query.sort(),
                                query.order(),
                                result.tokens().getLast().position(),
                                shownBefore + rows.size())
                        .encode())
                : Optional.empty();
        return new ScannerPage(
                rows, next, result.total(), sources, result.excludedByUnknown(), result.pendingNewTokens());
    }

    ScannerRow row(ListedToken token, Instant now) {
        ResolvedField price = token.fields().get(MarketField.PRICE);
        ResolvedField liquidity = token.fields().get(MarketField.LIQUIDITY);
        ResolvedField volume = token.fields().get(MarketField.VOLUME_24H);
        ResolvedField holders = token.fields().get(MarketField.HOLDERS);
        MarketValue age = ageMinutes(token.fields().get(MarketField.CREATED_AT).value(), now);
        int informed = (int) List.of(price.value(), liquidity.value(), volume.value(), holders.value(), age).stream()
                .filter(MarketValue::isKnown)
                .count();
        return new ScannerRow(
                token.token().chain(),
                token.token().mint(),
                token.symbol(),
                token.name(),
                informed,
                COVERAGE_TOTAL,
                price,
                liquidity,
                volume,
                holders,
                age,
                riskOf(token));
    }

    private AssessmentResult riskOf(ListedToken token) {
        ListedRisk listed = token.risk();
        if (listed.status().isEmpty()) {
            return risk.assess(token.token());
        }
        if ("AVAILABLE".equals(listed.status().get())) {
            return new AssessmentResult.Available(
                    listed.score().orElseThrow(),
                    RiskBand.valueOf(listed.band().orElseThrow()),
                    listed.weightSetVersion().orElseThrow());
        }
        return new AssessmentResult.Unavailable(
                UnavailableReason.valueOf(listed.unavailableReason().orElseThrow()));
    }

    /** Whole minutes since creation (BR2.8); an unknown creation time keeps the age unknown, never zero. */
    static MarketValue ageMinutes(MarketValue createdAt, Instant now) {
        if (createdAt instanceof MarketValue.Known known) {
            Instant created = Instant.ofEpochSecond(known.value().longValueExact());
            long minutes = Math.max(0, Duration.between(created, now).toMinutes());
            return new MarketValue.Known(
                    BigDecimal.valueOf(minutes), known.source(), known.observedAt(), known.stale());
        }
        return createdAt;
    }
}
