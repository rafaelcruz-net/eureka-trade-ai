package com.memetrade.decision.domain;

import com.memetrade.bots.domain.StrategyTemplate;
import com.memetrade.bots.domain.StrategyTemplates;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builders of the catalogue D of functional-spec.md, as TEXT values written in the tests (unit-test-instructions.md
 * "Dados de teste"). Base: token with 6 places, default parameters, last closed bucket {@code b_n} fresh. The slot
 * starts at 12:00:00 UTC, the cycle runs at 12:01:00 and {@code b_n} is the bucket that started at 11:55:00.
 */
public final class DecisionScenarios {

    public static final Instant SLOT = Instant.parse("2026-10-28T12:00:00Z");
    public static final Instant NOW = SLOT.plusSeconds(60);
    public static final Instant LAST_BUCKET = SLOT.minus(Duration.ofMinutes(5));
    public static final SourceRef SOURCE = new SourceRef("sim-u5", "Simulado U5", true);

    /** Synthetic mints in alphabetical order (never real). */
    public static final TokenId TOKEN_A = new TokenId("solana", "So1aNaTestMintAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    public static final TokenId TOKEN_B = new TokenId("solana", "So1aNaTestMintBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
    public static final TokenId TOKEN_C = new TokenId("solana", "So1aNaTestMintCCCCCCCCCCCCCCCCCCCCCCCCCCCC");

    private DecisionScenarios() {}

    /** A request builder with the defaults of the catalogue base. */
    public static Builder momentum() {
        return new Builder(StrategyTemplates.MOMENTUM_VOLUME_V1);
    }

    public static Builder range() {
        return new Builder(StrategyTemplates.RANGE_BREAKOUT_V1);
    }

    public static MarketValue known(String value) {
        return known(value, NOW.minusSeconds(30));
    }

    public static MarketValue known(String value, Instant observedAt) {
        return new MarketValue.Known(
                new BigDecimal(value),
                SOURCE,
                observedAt,
                Duration.between(observedAt, NOW).toMinutes() >= 5);
    }

    public static MarketValue unknown() {
        return new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.of(SOURCE));
    }

    /**
     * A window ending at {@code b_n}: {@code prices[i]} and {@code volumes[i]} are bucket {@code b_(n-len+1+i)}; a
     * {@code null} is UNKNOWN. Each value is observed 30 s before its bucket closes.
     */
    public static PriceVolumeWindow window(TokenId token, String[] prices, String[] volumes) {
        List<PriceVolumeWindow.Point> points = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            Instant bucket = LAST_BUCKET.minus(Duration.ofMinutes(5L * (prices.length - 1 - i)));
            Instant observed = bucket.plus(Duration.ofMinutes(5)).minusSeconds(30);
            points.add(new PriceVolumeWindow.Point(
                    bucket,
                    prices[i] == null ? unknown() : known(prices[i], observed),
                    volumes[i] == null ? unknown() : known(volumes[i], observed)));
        }
        Instant from = points.isEmpty() ? LAST_BUCKET : points.getFirst().bucketStart();
        return new PriceVolumeWindow(token, from, LAST_BUCKET, points);
    }

    /** {@code count} buckets with the same price and volume. */
    public static String[] repeat(String value, int count) {
        String[] values = new String[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    public static String[] append(String[] values, String last) {
        String[] longer = java.util.Arrays.copyOf(values, values.length + 1);
        longer[values.length] = last;
        return longer;
    }

    public static final class Builder {
        private final StrategyTemplate template;
        private final Map<String, BigDecimal> parameters = new LinkedHashMap<>();
        private final List<TokenInputs> tokens = new ArrayList<>();
        private final List<PositionInput> positions = new ArrayList<>();
        private BigDecimal balance = new BigDecimal("1000.000000");
        private BigDecimal maxOrderSize = new BigDecimal("100.000000");

        private Builder(StrategyTemplate template) {
            this.template = template;
            template.parameters().forEach(spec -> parameters.put(spec.key(), spec.defaultValue()));
        }

        public Builder parameter(String key, String value) {
            parameters.put(key, new BigDecimal(value));
            return this;
        }

        public Builder balance(String value) {
            this.balance = new BigDecimal(value);
            return this;
        }

        public Builder maxOrderSize(String value) {
            this.maxOrderSize = new BigDecimal(value);
            return this;
        }

        /** A token with its window, a reference price observed 30 s ago, 6 places and band LOW. */
        public Builder token(TokenId token, String[] prices, String[] volumes, String referencePrice) {
            return token(
                    token,
                    window(token, prices, volumes),
                    referencePrice == null ? unknown() : known(referencePrice),
                    new AssessmentResult.Available(20, RiskBand.LOW, 1));
        }

        public Builder token(
                TokenId token, PriceVolumeWindow window, MarketValue referencePrice, AssessmentResult assessment) {
            tokens.add(new TokenInputs(token, window, referencePrice, known("6", NOW.minusSeconds(30)), assessment));
            return this;
        }

        public Builder tokenWithBand(TokenId token, String[] prices, String[] volumes, String price, RiskBand band) {
            return token(
                    token,
                    window(token, prices, volumes),
                    known(price),
                    new AssessmentResult.Available(band == RiskBand.HIGH ? 80 : 20, band, 1));
        }

        public Builder tokenWithoutAssessment(TokenId token, String[] prices, String[] volumes, String price) {
            return token(
                    token,
                    window(token, prices, volumes),
                    known(price),
                    new AssessmentResult.Unavailable(UnavailableReason.INSUFFICIENT_COVERAGE));
        }

        /** An open position of {@code quantity} with cost {@code costBasis}, opened {@code minutesAgo} before now. */
        public Builder position(TokenId token, String quantity, String costBasis, long minutesAgo) {
            positions.add(new PositionInput(
                    token,
                    new BigDecimal(quantity),
                    new BigDecimal(costBasis),
                    NOW.minus(Duration.ofMinutes(minutesAgo))));
            return this;
        }

        public DecisionRequest build() {
            return new DecisionRequest(
                    template.templateId(),
                    template.version(),
                    parameters,
                    1,
                    maxOrderSize,
                    balance,
                    tokens,
                    positions,
                    SLOT,
                    NOW);
        }
    }
}
