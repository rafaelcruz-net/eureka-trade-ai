package com.memetrade.decision.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.decision.domain.SelectionRule.Field;
import com.memetrade.decision.domain.SelectionRule.Operator;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery.ListedRisk;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.MarketListingQuery.Position;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * BR4.8 and BR4.9 (Passo 7, written before the resolver; NFR4.6): explicit list first, then the tokens the filters
 * approve (inclusive bounds, UNKNOWN field out, by liquidity descending), at most 10 in total, then the tokens with an
 * open position, without repetition.
 */
@Tag("u5")
class TokenSelectionResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:01:00Z");

    @Test
    void theExplicitListComesFirstThenTheFilteredTokensByLiquidity() {
        TokenId explicit = token("Expl");
        List<ListedToken> listed = List.of(listed("Low", "20000"), listed("High", "90000"), listed("Mid", "50000"));

        List<TokenId> tokens = TokenSelectionResolver.resolve(
                List.of(explicit), List.of(rule(Field.LIQUIDITY, Operator.GTE, "50000")), listed, List.of(), NOW);

        assertThat(tokens).containsExactly(explicit, token("High"), token("Mid"));
    }

    @Test
    void theBoundsAreInclusiveAndAnUnknownFieldLeavesTheTokenOut() {
        List<ListedToken> listed = List.of(listed("Exact", "50000"), unknownLiquidity("Unk"));

        assertThat(TokenSelectionResolver.resolve(
                        List.of(), List.of(rule(Field.LIQUIDITY, Operator.LTE, "50000")), listed, List.of(), NOW))
                .containsExactly(token("Exact"));
    }

    @Test
    void atMostTenTokensComeFromTheSelectionAndThePositionsAreAddedWithoutRepetition() {
        List<TokenId> explicit = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            explicit.add(token("Ex" + "ABCDEFGH".charAt(i)));
        }
        List<ListedToken> listed = List.of(listed("FA", "3"), listed("FB", "2"), listed("FC", "1"));
        TokenId held = token("Held");

        List<TokenId> tokens = TokenSelectionResolver.resolve(
                explicit,
                List.of(rule(Field.LIQUIDITY, Operator.GTE, "0")),
                listed,
                List.of(held, explicit.getFirst()),
                NOW);

        assertThat(tokens).hasSize(11);
        assertThat(tokens.subList(0, 10)).doesNotContain(token("FC"), held);
        assertThat(tokens.getLast()).isEqualTo(held);
    }

    @Test
    void withoutRulesOnlyTheExplicitListAndThePositionsCount() {
        List<ListedToken> listed = List.of(listed("Any", "99999"));

        assertThat(TokenSelectionResolver.resolve(List.of(), List.of(), listed, List.of(), NOW))
                .isEmpty();
    }

    @Test
    void theAgeAndTheRiskScoreFiltersReadTheCreationTimeAndTheListedScore() {
        ListedToken young = withAgeAndScore("Young", NOW.minusSeconds(30 * 60), 20);
        ListedToken old = withAgeAndScore("Old", NOW.minusSeconds(300 * 60), 70);

        assertThat(TokenSelectionResolver.resolve(
                        List.of(),
                        List.of(rule(Field.AGE_MINUTES, Operator.GTE, "60")),
                        List.of(young, old),
                        List.of(),
                        NOW))
                .containsExactly(token("Old"));
        assertThat(TokenSelectionResolver.resolve(
                        List.of(),
                        List.of(rule(Field.RISK_SCORE, Operator.LTE, "20")),
                        List.of(young, old),
                        List.of(),
                        NOW))
                .containsExactly(token("Young"));
    }

    // ----- helpers (synthetic tokens) -----

    private static TokenId token(String name) {
        return new TokenId("solana", "So1aNa" + name + "Mint");
    }

    private static SelectionRule rule(Field field, Operator operator, String value) {
        return new SelectionRule(field, operator, new BigDecimal(value));
    }

    private static ListedToken listed(String name, String liquidity) {
        Map<MarketField, ResolvedField> fields = new EnumMap<>(MarketField.class);
        fields.put(MarketField.LIQUIDITY, ResolvedField.of(known(liquidity)));
        return new ListedToken(
                token(name), name, name, fields, risk(Optional.empty()), new Position(Optional.empty(), "m", "c"));
    }

    private static ListedToken unknownLiquidity(String name) {
        Map<MarketField, ResolvedField> fields = new EnumMap<>(MarketField.class);
        fields.put(MarketField.LIQUIDITY, ResolvedField.of(DecisionScenarios.unknown()));
        return new ListedToken(
                token(name), name, name, fields, risk(Optional.empty()), new Position(Optional.empty(), "m", "c"));
    }

    private static ListedToken withAgeAndScore(String name, Instant createdAt, int score) {
        Map<MarketField, ResolvedField> fields = new EnumMap<>(MarketField.class);
        fields.put(MarketField.CREATED_AT, ResolvedField.of(known(String.valueOf(createdAt.getEpochSecond()))));
        return new ListedToken(
                token(name), name, name, fields, risk(Optional.of(score)), new Position(Optional.empty(), "m", "c"));
    }

    private static ListedRisk risk(Optional<Integer> score) {
        return new ListedRisk(Optional.of("AVAILABLE"), score, Optional.empty(), Optional.empty(), Optional.of(1));
    }

    private static MarketValue known(String value) {
        return DecisionScenarios.known(value);
    }
}
