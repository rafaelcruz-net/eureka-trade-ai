package com.memetrade.decision.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.RiskBand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Property test with a FIXED seed (Passo 7; NFR4.1, NFR4.6, BR4.2), written before the engine: on 600 random scenarios
 * of prices, volumes, unknown values, bands and positions, no BUY or SELL leaves without the signal and the rule that
 * explain it, a SELL never sells more than the position, a BUY never spends more than the maximum per order, every
 * NO_TRADE has its reason and the same input gives the same decision.
 */
@Tag("u5")
class DecisionPropertyTest {

    private static final long SEED = 20_261_028L;
    private static final List<TokenId> TOKENS =
            List.of(DecisionScenarios.TOKEN_A, DecisionScenarios.TOKEN_B, DecisionScenarios.TOKEN_C);

    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void everyOrderIsExplainedAndBoundedOnSixHundredRandomScenarios() {
        Random random = new Random(SEED);
        int buys = 0;
        int sells = 0;
        for (int run = 0; run < 600; run++) {
            DecisionRequest request = scenario(random);
            DecisionOutcome outcome = engine.decide(request);

            assertThat(engine.decide(request)).as("deterministic, run " + run).isEqualTo(outcome);
            switch (outcome.action()) {
                case BUY -> {
                    buys++;
                    assertBuyIsExplained(request, outcome);
                }
                case SELL -> {
                    sells++;
                    assertSellIsExplained(request, outcome);
                }
                case NO_TRADE ->
                    assertThat(outcome.noTradeReason()).as("run " + run).isPresent();
                case HOLD -> assertThat(request.positions()).as("run " + run).isNotEmpty();
            }
        }
        assertThat(buys).as("the seed exercises buys").isPositive();
        assertThat(sells).as("the seed exercises sells").isPositive();
    }

    private static void assertBuyIsExplained(DecisionRequest request, DecisionOutcome outcome) {
        TokenId token = outcome.token().orElseThrow();
        List<Factor> factors = factorsOf(outcome, token);
        assertThat(factors).filteredOn(f -> f.name().equals("signalStrength")).isNotEmpty();
        assertThat(factors).filteredOn(f -> f.name().equals("riskBand")).allMatch(Factor::satisfied);
        assertThat(request.positions()).noneMatch(p -> p.token().equals(token));
        BigDecimal price = ((com.memetrade.marketdata.api.MarketValue.Known) request.tokens().stream()
                        .filter(t -> t.token().equals(token))
                        .findFirst()
                        .orElseThrow()
                        .referencePrice())
                .value();
        BigDecimal quantity = outcome.requestedQuantity().orElseThrow();
        assertThat(quantity.multiply(price)).isLessThanOrEqualTo(request.maxOrderSize());
        assertThat(quantity.scale()).isLessThanOrEqualTo(6);
    }

    private static void assertSellIsExplained(DecisionRequest request, DecisionOutcome outcome) {
        TokenId token = outcome.token().orElseThrow();
        PositionInput position = request.positions().stream()
                .filter(p -> p.token().equals(token))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SELL without a position"));
        assertThat(outcome.requestedQuantity()).contains(position.quantity());
        assertThat(outcome.exitReason()).isPresent();
        assertThat(factorsOf(outcome, token)).anyMatch(Factor::satisfied);
    }

    private static List<Factor> factorsOf(DecisionOutcome outcome, TokenId token) {
        return outcome.factors().stream().filter(f -> f.token().equals(token)).collect(Collectors.toList());
    }

    private static DecisionRequest scenario(Random random) {
        DecisionScenarios.Builder builder = DecisionScenarios.momentum()
                .balance(String.valueOf(random.nextInt(5000)) + "." + String.format("%06d", random.nextInt(1_000_000)));
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            TokenId token = TOKENS.get(i);
            String[] prices = new String[4];
            String[] volumes = new String[4];
            for (int b = 0; b < 4; b++) {
                prices[b] = random.nextInt(12) == 0 ? null : decimal(random, 90, 120);
                volumes[b] = random.nextInt(12) == 0 ? null : String.valueOf(900_000 + random.nextInt(500_000));
            }
            String reference = random.nextInt(10) == 0 ? null : decimal(random, 85, 125);
            RiskBand band = RiskBand.values()[random.nextInt(3)];
            if (reference == null) {
                builder.token(token, prices, volumes, null);
            } else {
                builder.tokenWithBand(token, prices, volumes, reference, band);
            }
            if (random.nextBoolean()) {
                builder.position(token, decimal(random, 1, 300), decimal(random, 50, 150), random.nextInt(400));
            }
        }
        return builder.build();
    }

    /** A decimal with two places between {@code low} and {@code high} hundredths of one (e.g. 0,90 to 1,20). */
    private static String decimal(Random random, int low, int high) {
        int hundredths = low + random.nextInt(high - low + 1);
        return new BigDecimal(hundredths).movePointLeft(2).toPlainString();
    }
}
