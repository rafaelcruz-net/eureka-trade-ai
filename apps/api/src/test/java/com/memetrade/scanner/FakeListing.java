package com.memetrade.scanner;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test double of {@link MarketListingQuery}: pages over a fixed list in the given order, records every request and
 * answers the configured source states. Not a reimplementation of the SQL (that is covered by ScannerListQueryIT).
 */
public final class FakeListing implements MarketListingQuery {

    public static final SourceRef SOURCE = new SourceRef("sim-a", "Fonte simulada A", true);

    public final List<ListedToken> tokens = new ArrayList<>();
    public final List<ListingRequest> requests = new ArrayList<>();
    public List<SourceStatus> sources =
            new ArrayList<>(List.of(new SourceStatus(SOURCE, SourceStatus.State.OK, Optional.empty())));
    public long excluded;
    public long pending;

    @Override
    public ListingResult list(ListingRequest request) {
        requests.add(request);
        int start = request.after().map(after -> indexOf(after.mint()) + 1).orElse(0);
        int end = Math.min(tokens.size(), start + request.limit());
        return new ListingResult(
                List.copyOf(tokens.subList(start, end)), end < tokens.size(), tokens.size(), excluded, pending);
    }

    @Override
    public List<SourceStatus> sources() {
        return sources;
    }

    public void sources(SourceStatus.State... states) {
        sources = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            sources.add(new SourceStatus(
                    new SourceRef("sim-" + (char) ('a' + i), "Fonte " + i, true), states[i], Optional.empty()));
        }
    }

    /** A token with KNOWN price, liquidity and volume, UNKNOWN holders, the given creation time and risk. */
    public ListedToken add(String mint, Instant at, Optional<Instant> created, ListedRisk risk) {
        Map<MarketField, ResolvedField> fields = new EnumMap<>(MarketField.class);
        fields.put(MarketField.PRICE, ResolvedField.of(known("0.00001234", at)));
        fields.put(MarketField.LIQUIDITY, ResolvedField.of(known("0", at)));
        fields.put(MarketField.VOLUME_24H, ResolvedField.of(known("100.5", at)));
        fields.put(MarketField.HOLDERS, ResolvedField.of(MarketValue.Unknown.notProvided(SOURCE)));
        fields.put(
                MarketField.CREATED_AT,
                ResolvedField.of(created.<MarketValue>map(c -> known(Long.toString(c.getEpochSecond()), at))
                        .orElse(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)))));
        ListedToken token = new ListedToken(
                new TokenId("solana", mint),
                "TST",
                null,
                fields,
                risk,
                new Position(created.map(c -> BigDecimal.valueOf(c.getEpochSecond())), mint, "solana"));
        tokens.add(token);
        return token;
    }

    public static ListedRisk available(int score, String band) {
        return new ListedRisk(
                Optional.of("AVAILABLE"), Optional.of(score), Optional.of(band), Optional.empty(), Optional.of(1));
    }

    public static ListedRisk none() {
        return new ListedRisk(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static MarketValue.Known known(String value, Instant at) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, at, false);
    }

    private int indexOf(String mint) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).token().mint().equals(mint)) {
                return i;
            }
        }
        return -1;
    }
}
