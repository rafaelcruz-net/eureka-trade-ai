package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The tokens a cycle evaluates (BR4.8, BR4.9): the explicit list first, then the scanner tokens every rule accepts
 * (inclusive bounds; a rule over an UNKNOWN field leaves the token out), by liquidity descending, at most
 * {@value #MAX_SELECTED} in total; then every token with an open position, without repetition.
 */
public final class TokenSelectionResolver {

    public static final int MAX_SELECTED = 10;

    private TokenSelectionResolver() {}

    public static List<TokenId> resolve(
            List<TokenId> explicit,
            List<SelectionRule> rules,
            List<ListedToken> listed,
            List<TokenId> positions,
            Instant now) {
        Set<TokenId> chosen = new LinkedHashSet<>();
        for (TokenId token : explicit) {
            if (chosen.size() < MAX_SELECTED) {
                chosen.add(token);
            }
        }
        if (!rules.isEmpty()) {
            List<ListedToken> approved = new ArrayList<>();
            for (ListedToken candidate : listed) {
                if (rules.stream().allMatch(rule -> accepts(rule, candidate, now))) {
                    approved.add(candidate);
                }
            }
            approved.sort(Comparator.<ListedToken, BigDecimal>comparing(
                            token -> known(token, MarketField.LIQUIDITY).orElse(null),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(token -> token.token().mint()));
            for (ListedToken token : approved) {
                if (chosen.size() >= MAX_SELECTED) {
                    break;
                }
                chosen.add(token.token());
            }
        }
        chosen.addAll(positions);
        return List.copyOf(chosen);
    }

    private static boolean accepts(SelectionRule rule, ListedToken token, Instant now) {
        Optional<BigDecimal> observed =
                switch (rule.field()) {
                    case LIQUIDITY -> known(token, MarketField.LIQUIDITY);
                    case VOLUME_24H -> known(token, MarketField.VOLUME_24H);
                    case HOLDERS -> known(token, MarketField.HOLDERS);
                    case AGE_MINUTES ->
                        known(token, MarketField.CREATED_AT)
                                .map(created -> BigDecimal.valueOf((now.getEpochSecond() - created.longValue()) / 60));
                    case RISK_SCORE -> token.risk().score().map(BigDecimal::valueOf);
                };
        return observed.map(rule::accepts).orElse(false);
    }

    private static Optional<BigDecimal> known(ListedToken token, MarketField field) {
        ResolvedField resolved = token.fields().get(field);
        if (resolved != null && resolved.value() instanceof MarketValue.Known known) {
            return Optional.of(known.value());
        }
        return Optional.empty();
    }
}
