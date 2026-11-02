package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.ObservationValidator.DiscardReason;
import com.memetrade.marketdata.domain.ObservationValidator.Validated;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Validation at the port (NFR6.5): invalid tokens are discarded and counted by reason, the others go on. */
@Tag("u4")
class ObservationValidatorTest {

    private static final SourceRef SOURCE = new SourceRef("sim-a", "A", true);
    private static final SourceRef OTHER = new SourceRef("sim-b", "B", true);
    private static final Instant AT = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void aValidCollectionIsKeptWhole() {
        TokenInfo token = token("solana", SyntheticIds.mint(), "OK");
        Validated validated = validate(List.of(token), List.of(price(token, "0.000001")));

        assertThat(validated.tokens()).containsExactly(token);
        assertThat(validated.values().get(token.token())).containsKey(MarketField.PRICE);
        assertThat(validated.discardedTotal()).isZero();
    }

    @Test
    void anInvalidTokenIsDiscardedAndCountedWhileTheOthersGoOn() {
        TokenInfo good = token("solana", SyntheticIds.mint(), "GOOD");
        TokenInfo badMint = token("solana", "0OIl" + SyntheticIds.mint().substring(4), "BAD");
        TokenInfo badChain = token("Solana!", SyntheticIds.mint(), "BAD");
        TokenInfo control = token("solana", SyntheticIds.mint(), "EVIL\u0007");
        TokenInfo bidi = token("solana", SyntheticIds.mint(), "X‮evil");

        Validated validated = validate(List.of(good, badMint, badChain, control, bidi), List.of());

        assertThat(validated.tokens()).containsExactly(good);
        assertThat(validated.discarded()).containsEntry(DiscardReason.INVALID_FIELD, 4);
    }

    @Test
    void negativeTooPreciseOrForeignValuesDiscardTheirToken() {
        TokenInfo negative = token("solana", SyntheticIds.mint(), "NEG");
        TokenInfo scale = token("solana", SyntheticIds.mint(), "SCALE");
        TokenInfo digits = token("solana", SyntheticIds.mint(), "DIGITS");
        TokenInfo foreign = token("solana", SyntheticIds.mint(), "FOREIGN");
        TokenInfo zero = token("solana", SyntheticIds.mint(), "ZERO");
        List<Observation> observations = List.of(
                price(negative, "-1"),
                price(scale, "0.0000000000000000001"),
                price(digits, "1234567890123456789012345678901"),
                new Observation(
                        foreign.token(), MarketField.PRICE, new MarketValue.Known(BigDecimal.ONE, OTHER, AT, false)),
                price(zero, "0"));

        Validated validated = validate(List.of(negative, scale, digits, foreign, zero), observations);

        assertThat(validated.tokens()).containsExactly(zero);
        assertThat(validated.discarded()).containsEntry(DiscardReason.INVALID_FIELD, 4);
    }

    @Test
    void duplicatedTokensOrFieldsAndFractionalCreationTimesAreInvalid() {
        TokenInfo once = token("solana", SyntheticIds.mint(), "ONCE");
        TokenInfo twice = token("solana", SyntheticIds.mint(), "TWICE");
        TokenInfo creation = token("solana", SyntheticIds.mint(), "CREATED");
        List<Observation> observations = List.of(
                price(twice, "1"),
                price(twice, "2"),
                new Observation(
                        creation.token(),
                        MarketField.CREATED_AT,
                        new MarketValue.Known(new BigDecimal("1700000000.5"), SOURCE, AT, false)));

        Validated validated = validate(List.of(once, once, twice, creation), observations);

        assertThat(validated.tokens()).containsExactly(once);
        assertThat(validated.discarded()).containsEntry(DiscardReason.INVALID_FIELD, 3);
    }

    @Test
    void anUnknownValueKeepsItsTokenAndAMissingFieldIsSimplyAbsent() {
        TokenInfo token = token("solana", SyntheticIds.mint(), "UNK");
        Validated validated = validate(
                List.of(token),
                List.of(new Observation(token.token(), MarketField.HOLDERS, MarketValue.Unknown.notProvided(SOURCE))));

        assertThat(validated.tokens()).containsExactly(token);
        assertThat(validated.values().get(token.token()).get(MarketField.HOLDERS))
                .isInstanceOf(MarketValue.Unknown.class);
        assertThat(validated.values().get(token.token())).doesNotContainKey(MarketField.PRICE);
    }

    @Test
    void textsAndDecimalsHaveTheirLimits() {
        assertThat(ObservationValidator.validToken(
                        new TokenInfo(new TokenId("solana", SyntheticIds.mint()), "S".repeat(33), null, 6)))
                .isFalse();
        assertThat(ObservationValidator.validToken(
                        new TokenInfo(new TokenId("solana", SyntheticIds.mint()), "S", "N".repeat(121), 6)))
                .isFalse();
        assertThat(ObservationValidator.validToken(
                        new TokenInfo(new TokenId("solana", SyntheticIds.mint()), "S", null, 19)))
                .isFalse();
        assertThat(ObservationValidator.validToken(
                        new TokenInfo(new TokenId("solana", SyntheticIds.mint()), "S", null, 18)))
                .isTrue();
        assertThat(ObservationValidator.validDecimal(new BigDecimal("0.000000000000000001")))
                .isTrue();
    }

    @Test
    void atMostFiveThousandTokensAreKeptAndTheRestIsCountedAsTooMany() {
        List<TokenInfo> tokens = new ArrayList<>();
        for (int i = 0; i < ObservationValidator.MAX_TOKENS_PER_COLLECTION + 3; i++) {
            tokens.add(token("solana", SyntheticIds.mint(), "T" + i));
        }

        Validated validated = validate(tokens, List.of());

        assertThat(validated.tokens()).hasSize(5_000);
        assertThat(validated.discarded()).containsEntry(DiscardReason.TOO_MANY, 3);
    }

    private static Validated validate(List<TokenInfo> tokens, List<Observation> observations) {
        return ObservationValidator.validate(new FetchResult(tokens, observations), SOURCE);
    }

    private static TokenInfo token(String chain, String mint, String symbol) {
        return new TokenInfo(new TokenId(chain, mint), symbol, symbol + " name", 6);
    }

    private static Observation price(TokenInfo token, String value) {
        return new Observation(
                token.token(), MarketField.PRICE, new MarketValue.Known(new BigDecimal(value), SOURCE, AT, false));
    }

    @SuppressWarnings("unused")
    private static Optional<SourceRef> none() {
        return Optional.empty();
    }
}
