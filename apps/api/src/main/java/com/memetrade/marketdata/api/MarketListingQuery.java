package com.memetrade.marketdata.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public read interface of the scanner list (F3, US2.2, US2.3, US3.2), used by the Scanner only; the Scanner never
 * reads MarketData tables. One query per page (performance-design.md, Q3): the photo of tokens, the displayed value of
 * each field, the stored assessment, the filters with "passes" and "unknown", the counters and the page, continued by
 * tuple comparison after the position of the cursor, never OFFSET.
 */
public interface MarketListingQuery {

    ListingResult list(ListingRequest request);

    /** Status of every enabled source, ordered by id. */
    List<SourceStatus> sources();

    enum SortField {
        AGE,
        LIQUIDITY,
        VOLUME_24H,
        HOLDERS,
        RISK_SCORE
    }

    enum SortOrder {
        ASC,
        DESC
    }

    /**
     * Active filters (BR2.1, inclusive bounds); an empty filter is not active.
     *
     * @param maxAgeMinutes maximum age in whole minutes, compared with the exact duration (BR2.8)
     * @param sourceId tokens informed by this source, when it is not FAILED
     */
    record Filters(
            Optional<BigDecimal> minLiquidity,
            Optional<Long> maxAgeMinutes,
            Optional<BigDecimal> minVolume24h,
            Optional<BigDecimal> minHolders,
            Optional<Integer> maxRiskScore,
            Optional<String> sourceId) {

        public static Filters none() {
            return new Filters(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        public boolean anyActive() {
            return minLiquidity.isPresent()
                    || maxAgeMinutes.isPresent()
                    || minVolume24h.isPresent()
                    || minHolders.isPresent()
                    || maxRiskScore.isPresent()
                    || sourceId.isPresent();
        }
    }

    /**
     * Position of the last item of a page: its sort value (empty when UNKNOWN, those come last) and the tie-breakers
     * mint and chain (BR2.5). For AGE the sort value is the creation time in epoch seconds.
     */
    record Position(Optional<BigDecimal> sortValue, String mint, String chain) {}

    /**
     * @param snapshotAt only tokens first seen at or before it belong to the pages (BR2.6)
     * @param limit page size (the query reads one more to know whether a next page exists)
     */
    record ListingRequest(
            Instant snapshotAt,
            Filters filters,
            SortField sort,
            SortOrder order,
            Optional<Position> after,
            int limit) {}

    /**
     * The stored assessment joined by the list query; {@code status} is empty when the token has none yet. Plain
     * values, so MarketData never depends on the RiskAssessment module.
     */
    record ListedRisk(
            Optional<String> status,
            Optional<Integer> score,
            Optional<String> band,
            Optional<String> unavailableReason,
            Optional<Integer> weightSetVersion) {}

    /** A token of the page with every field resolved now (F2) and its stored assessment. */
    record ListedToken(
            TokenId token,
            String symbol,
            String name,
            Map<MarketField, ResolvedField> fields,
            ListedRisk risk,
            Position position) {}

    /**
     * @param total tokens of the photo that pass every active filter
     * @param excludedByUnknown tokens of the photo left out ONLY by an unknown filtered field (BR2.2), each once
     * @param pendingNewTokens tokens first seen after the photo that pass the filters (BR2.6)
     */
    record ListingResult(
            List<ListedToken> tokens, boolean hasMore, long total, long excludedByUnknown, long pendingNewTokens) {}
}
