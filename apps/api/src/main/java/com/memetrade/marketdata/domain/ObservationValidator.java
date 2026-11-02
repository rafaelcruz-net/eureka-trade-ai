package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation at the {@code MarketSourceAdapter} port, before anything is written (security-design.md, NFR6.5,
 * NFR6.6, F1 step 4). Source data is untrusted input: a token whose identity, text or any value breaks a rule is
 * discarded with all its observations and counted by reason; the other tokens go on. At most
 * {@value #MAX_TOKENS_PER_COLLECTION} tokens are kept per collection; the rest is discarded as {@code TOO_MANY}.
 */
public final class ObservationValidator {

    public static final int MAX_TOKENS_PER_COLLECTION = 5_000;
    static final int MAX_DIGITS = 30;
    static final int MAX_SCALE = 18;
    static final int MAX_SYMBOL = 32;
    static final int MAX_NAME = 120;

    private static final Pattern CHAIN = Pattern.compile("[a-z0-9]{1,64}");
    private static final Pattern MINT = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");

    /** Why a token was discarded; the label of {@code market_collection_discarded_total}. */
    public enum DiscardReason {
        INVALID_FIELD,
        TOO_MANY
    }

    /**
     * The tokens kept, with their observed values by field (fields the source did not send are absent here), and the
     * count of discarded tokens by reason.
     */
    public record Validated(
            List<TokenInfo> tokens,
            Map<TokenId, Map<MarketField, MarketValue>> values,
            Map<DiscardReason, Integer> discarded) {

        public int discardedTotal() {
            return discarded.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private ObservationValidator() {}

    public static Validated validate(FetchResult result, SourceRef source) {
        Map<DiscardReason, Integer> discarded = new EnumMap<>(DiscardReason.class);
        Map<TokenId, Map<MarketField, MarketValue>> byToken = groupObservations(result.observations(), source);
        Set<TokenId> seen = new HashSet<>();
        List<TokenInfo> kept = new ArrayList<>();
        Map<TokenId, Map<MarketField, MarketValue>> values = new LinkedHashMap<>();
        for (TokenInfo token : result.tokens()) {
            if (!seen.add(token.token()) || !validToken(token) || byToken.get(token.token()) == INVALID) {
                count(discarded, DiscardReason.INVALID_FIELD);
                continue;
            }
            if (kept.size() >= MAX_TOKENS_PER_COLLECTION) {
                count(discarded, DiscardReason.TOO_MANY);
                continue;
            }
            kept.add(token);
            values.put(token.token(), byToken.getOrDefault(token.token(), Map.of()));
        }
        return new Validated(List.copyOf(kept), values, discarded);
    }

    /** Marker of a token with at least one invalid or duplicated observation. */
    private static final Map<MarketField, MarketValue> INVALID =
            java.util.Collections.unmodifiableMap(new EnumMap<>(MarketField.class));

    private static Map<TokenId, Map<MarketField, MarketValue>> groupObservations(
            List<Observation> observations, SourceRef source) {
        Map<TokenId, Map<MarketField, MarketValue>> byToken = new LinkedHashMap<>();
        for (Observation observation : observations) {
            Map<MarketField, MarketValue> fields =
                    byToken.computeIfAbsent(observation.token(), t -> new EnumMap<>(MarketField.class));
            if (fields == INVALID) {
                continue;
            }
            boolean valid = observation.field() != null
                    && observation.value() != null
                    && validValue(observation.field(), observation.value(), source)
                    && !fields.containsKey(observation.field());
            if (valid) {
                fields.put(observation.field(), observation.value());
            } else {
                byToken.put(observation.token(), INVALID);
            }
        }
        return byToken;
    }

    static boolean validToken(TokenInfo token) {
        return token.token() != null
                && token.token().chain() != null
                && CHAIN.matcher(token.token().chain()).matches()
                && token.token().mint() != null
                && MINT.matcher(token.token().mint()).matches()
                && validText(token.symbol(), MAX_SYMBOL, true)
                && validText(token.name(), MAX_NAME, false)
                && token.decimals() >= 0
                && token.decimals() <= MAX_SCALE;
    }

    static boolean validValue(MarketField field, MarketValue value, SourceRef source) {
        return switch (value) {
            case MarketValue.Known known ->
                source.equals(known.source())
                        && validDecimal(known.value())
                        && (field != MarketField.CREATED_AT
                                || known.value().stripTrailingZeros().scale() <= 0);
            case MarketValue.Unknown unknown ->
                unknown.source().map(source::equals).orElse(true);
        };
    }

    static boolean validDecimal(BigDecimal value) {
        return value.signum() >= 0 && value.precision() <= MAX_DIGITS && value.scale() <= MAX_SCALE;
    }

    static boolean validText(String text, int max, boolean required) {
        if (text == null) {
            return !required;
        }
        if ((required && text.isBlank()) || text.length() > max) {
            return false;
        }
        return text.codePoints().noneMatch(ObservationValidator::isControl);
    }

    private static boolean isControl(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    private static void count(Map<DiscardReason, Integer> discarded, DiscardReason reason) {
        discarded.merge(reason, 1, Integer::sum);
    }
}
