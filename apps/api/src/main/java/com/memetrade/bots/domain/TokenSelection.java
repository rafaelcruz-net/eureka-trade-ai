package com.memetrade.bots.domain;

import com.memetrade.decision.domain.SelectionRule;
import com.memetrade.marketdata.api.TokenId;
import java.util.List;

/**
 * The token selection embedded in a bot (entities.md, Q10): an explicit list of up to 10 tokens and up to 5 scanner
 * filters. At least one token or one rule is required from step 1 on (BR1.3).
 */
public record TokenSelection(List<TokenId> tokens, List<SelectionRule> rules) {

    public static final int MAX_TOKENS = 10;
    public static final int MAX_RULES = 5;

    public TokenSelection {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static TokenSelection empty() {
        return new TokenSelection(List.of(), List.of());
    }

    public boolean isEmpty() {
        return tokens.isEmpty() && rules.isEmpty();
    }
}
