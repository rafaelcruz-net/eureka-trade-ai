package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.MarketListingQuery.ListingPage;
import com.memetrade.marketdata.api.MarketValue;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal scanner list (C2 in U1): global market data, no customer data, no filters and no risk. The first page fixes
 * the cutoff and N; next pages reuse them from the cursor (BR3.6). Coverage counts KNOWN among price, liquidity,
 * 24h volume, holders and age (BR3.5). The age is derived from the creation time and keeps its source and freshness.
 */
public class ScannerService {

    public static final int COVERAGE_TOTAL = 5;

    private final MarketListingQuery market;
    private final Clock clock;

    public ScannerService(MarketListingQuery market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    public ScannerPage list(Optional<String> cursorText, int limit) {
        Optional<ScannerCursor> cursor = cursorText.filter(c -> !c.isBlank()).map(ScannerCursor::decode);
        Instant cutoff = cursor.map(ScannerCursor::cutoff).orElseGet(clock::instant);
        long total = cursor.map(ScannerCursor::total).orElseGet(() -> market.count(cutoff));
        ListingPage page = market.page(cursor.map(ScannerCursor::last), cutoff, limit);
        Instant now = clock.instant();
        List<ScannerRow> rows =
                page.tokens().stream().map(token -> row(token, now)).toList();
        Optional<String> next = page.hasMore()
                ? Optional.of(new ScannerCursor(cutoff, page.tokens().getLast().position(), total).encode())
                : Optional.empty();
        return new ScannerPage(rows, next, total, market.sources(), 0, 0);
    }

    static ScannerRow row(ListedToken token, Instant now) {
        Map<MarketField, MarketValue> values = token.values();
        MarketValue age = ageMinutes(values.get(MarketField.CREATED_AT), now);
        List<MarketValue> covered = List.of(
                values.get(MarketField.PRICE),
                values.get(MarketField.LIQUIDITY),
                values.get(MarketField.VOLUME_24H),
                values.get(MarketField.HOLDERS),
                age);
        int informed = (int) covered.stream().filter(MarketValue::isKnown).count();
        return new ScannerRow(
                token.token().chain(),
                token.token().mint(),
                token.symbol(),
                token.name(),
                informed,
                COVERAGE_TOTAL,
                values.get(MarketField.PRICE),
                values.get(MarketField.LIQUIDITY),
                values.get(MarketField.VOLUME_24H),
                values.get(MarketField.HOLDERS),
                age);
    }

    /** Whole minutes since creation; an unknown creation time keeps the age unknown (never zero). */
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
