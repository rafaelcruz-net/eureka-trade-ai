package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * What the decision found about ONE token: its factors and either an entry signal with its strength, an exit, or the
 * reason why it gives neither (BR4.1, BR4.5, BR4.7).
 */
record TokenEvaluation(
        TokenId token,
        List<Factor> factors,
        Optional<BigDecimal> strength,
        Optional<ExitReason> exit,
        Optional<NoTradeReason> reason) {

    TokenEvaluation {
        factors = List.copyOf(factors);
    }

    static TokenEvaluation signal(TokenId token, List<Factor> factors, BigDecimal strength) {
        return new TokenEvaluation(token, factors, Optional.of(strength), Optional.empty(), Optional.empty());
    }

    static TokenEvaluation exit(TokenId token, List<Factor> factors, ExitReason exit) {
        return new TokenEvaluation(token, factors, Optional.empty(), Optional.of(exit), Optional.empty());
    }

    static TokenEvaluation nothing(TokenId token, List<Factor> factors, Optional<NoTradeReason> reason) {
        return new TokenEvaluation(token, factors, Optional.empty(), Optional.empty(), reason);
    }
}
