package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.MarketListingQuery.Filters;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** A validated request of the scanner list (BR2.7): filters, order, page size and the cursor of the next page. */
public record ScannerQuery(Filters filters, SortField sort, SortOrder order, Optional<String> cursor, int limit) {

    /**
     * Default direction when {@code order} is not given: AGE ascending (youngest first), RISK_SCORE ascending (lowest
     * risk first), the other fields descending (largest first).
     */
    public static SortOrder defaultOrder(SortField sort) {
        return sort == SortField.AGE || sort == SortField.RISK_SCORE ? SortOrder.ASC : SortOrder.DESC;
    }

    /** Summary of the filters, kept in the cursor: a cursor of another query is refused (BR2.7). */
    public String filtersDigest() {
        String canonical = "minLiquidity=" + text(filters.minLiquidity())
                + "|maxAgeMinutes="
                + filters.maxAgeMinutes().map(String::valueOf).orElse("")
                + "|minVolume24h=" + text(filters.minVolume24h())
                + "|minHolders=" + text(filters.minHolders())
                + "|maxRiskScore=" + filters.maxRiskScore().map(String::valueOf).orElse("")
                + "|sourceId=" + filters.sourceId().orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", unavailable);
        }
    }

    private static String text(Optional<BigDecimal> value) {
        return value.map(v -> v.stripTrailingZeros().toPlainString()).orElse("");
    }
}
