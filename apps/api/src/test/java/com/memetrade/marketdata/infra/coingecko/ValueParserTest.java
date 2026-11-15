package com.memetrade.marketdata.infra.coingecko;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BR1.2 (US6.3), written BEFORE {@link ValueParser} (team.md: "dado de mercado ausente vira unknown, nunca zero"). Each
 * field is read as an exact decimal and checked against its range; absent is {@code Unknown(NOT_PROVIDED)} without
 * counting; present but invalid is {@code Unknown(NOT_PROVIDED)} and counted in {@code marketdata_invalid_values_total};
 * an informed zero is {@code Known 0}.
 */
class ValueParserTest {

    private static final SourceRef SOURCE = CoinGeckoSource.REF;
    private static final Instant AT = Instant.parse("2026-11-14T12:00:00Z");
    private static final JsonNodeFactory NODES = JsonNodeFactory.withExactBigDecimals(true);

    private final List<MarketField> invalid = new ArrayList<>();
    private final ValueParser parser = new ValueParser(SOURCE, invalid::add);

    @Test
    void aTextualPriceIsReadAsAnExactDecimalWithoutAnyLoss() {
        MarketValue value = parser.amount(MarketField.PRICE, NODES.textNode("0.000001234567890123"), AT);

        assertThat(known(value).value()).isEqualByComparingTo("0.000001234567890123");
        assertThat(known(value).value().toPlainString()).isEqualTo("0.000001234567890123");
        assertThat(known(value).observedAt()).isEqualTo(AT);
        assertThat(known(value).source()).isEqualTo(SOURCE);
        assertThat(invalid).isEmpty();
    }

    @Test
    void aJsonNumberIsReadAsAnExactDecimal() {
        MarketValue value =
                parser.amount(MarketField.LIQUIDITY, NODES.numberNode(new BigDecimal("123456.789012345678")), AT);

        assertThat(known(value).value().toPlainString()).isEqualTo("123456.789012345678");
    }

    @Test
    void anAbsentFieldIsUnknownNotProvidedAndIsNotCountedAsInvalid() {
        assertThat(parser.amount(MarketField.VOLUME_24H, null, AT)).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.amount(MarketField.PRICE, NODES.missingNode(), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).isEmpty();
    }

    @Test
    void aJsonNullIsUnknownNotProvidedNeverZero() {
        MarketValue value = parser.amount(MarketField.PRICE, NODES.nullNode(), AT);

        assertThat(value).isInstanceOf(MarketValue.Unknown.class);
        assertThat(((MarketValue.Unknown) value).reason()).isEqualTo(UnknownReason.NOT_PROVIDED);
        assertThat(invalid).isEmpty();
    }

    @Test
    void aNegativeAmountIsUnknownAndCountedForItsField() {
        MarketValue value = parser.amount(MarketField.LIQUIDITY, NODES.textNode("-1.5"), AT);

        assertThat(value).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).containsExactly(MarketField.LIQUIDITY);
    }

    @Test
    void anUnreadableTextIsUnknownAndCounted() {
        assertThat(parser.amount(MarketField.PRICE, NODES.textNode("abc"), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.amount(MarketField.PRICE, NODES.textNode("NaN"), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.amount(MarketField.PRICE, NODES.textNode(""), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).containsExactly(MarketField.PRICE, MarketField.PRICE, MarketField.PRICE);
    }

    @Test
    void anInformedZeroIsAKnownZeroNeverUnknown() {
        MarketValue text = parser.amount(MarketField.VOLUME_24H, NODES.textNode("0"), AT);
        MarketValue number = parser.amount(MarketField.LIQUIDITY, NODES.numberNode(BigDecimal.ZERO), AT);

        assertThat(known(text).value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(known(number).value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invalid).isEmpty();
    }

    @Test
    void aBooleanOrAnObjectIsUnknownAndCounted() {
        assertThat(parser.amount(MarketField.PRICE, NODES.booleanNode(true), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.amount(MarketField.PRICE, NODES.objectNode(), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).hasSize(2);
    }

    @Test
    void moreThanEighteenDecimalsAreRoundedHalfEvenAndAPositiveThatWouldBecomeZeroIsUnknown() {
        MarketValue rounded = parser.amount(MarketField.PRICE, NODES.textNode("0.0000000000000000125"), AT);
        MarketValue vanishing = parser.amount(MarketField.PRICE, NODES.textNode("0.0000000000000000001"), AT);

        assertThat(known(rounded).value().toPlainString()).isEqualTo("0.000000000000000012");
        assertThat(vanishing).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).containsExactly(MarketField.PRICE);
    }

    @Test
    void anAmountWithTooManyIntegerDigitsIsUnknownAndCounted() {
        assertThat(parser.amount(MarketField.LIQUIDITY, NODES.textNode("1e40"), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.amount(MarketField.LIQUIDITY, NODES.textNode("123456789012345678901"), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).hasSize(2);
    }

    @Test
    void holdersAreAWholeNumberAndAnIntegralDecimalIsAccepted() {
        assertThat(known(parser.holders(NODES.numberNode(1500), AT)).value()).isEqualByComparingTo("1500");
        assertThat(known(parser.holders(NODES.textNode("12.0"), AT)).value().scale())
                .isZero();
        assertThat(known(parser.holders(NODES.textNode("0"), AT)).value()).isEqualByComparingTo("0");
        assertThat(invalid).isEmpty();
    }

    @Test
    void fractionalOrNegativeHoldersAreUnknownAndCounted() {
        assertThat(parser.holders(NODES.textNode("12.5"), AT)).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.holders(NODES.numberNode(-3), AT)).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).containsExactly(MarketField.HOLDERS, MarketField.HOLDERS);
    }

    @Test
    void theCreationTimeIsAnIsoDateTurnedIntoEpochSeconds() {
        MarketValue value = parser.createdAt(NODES.textNode("2026-11-14T10:00:00Z"), AT);

        assertThat(known(value).value())
                .isEqualByComparingTo(
                        BigDecimal.valueOf(Instant.parse("2026-11-14T10:00:00Z").getEpochSecond()));
        assertThat(known(value).value().scale()).isZero();
        assertThat(parser.createdAt(NODES.textNode("2026-11-14T09:00:00.500+01:00"), AT))
                .isInstanceOf(MarketValue.Known.class);
    }

    @Test
    void aCreationTimeInTheFutureOrUnreadableIsUnknownAndCounted() {
        assertThat(parser.createdAt(NODES.textNode("2026-11-14T12:00:01Z"), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.createdAt(NODES.textNode("yesterday"), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.createdAt(NODES.numberNode(5), AT)).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(parser.createdAt(null, AT)).isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).containsExactly(MarketField.CREATED_AT, MarketField.CREATED_AT, MarketField.CREATED_AT);
    }

    @Test
    void aVeryLongNumberTextIsRefusedBeforeParsing() {
        String longText = "1".repeat(65);

        assertThat(parser.amount(MarketField.PRICE, NODES.textNode(longText), AT))
                .isEqualTo(MarketValue.Unknown.notProvided(SOURCE));
        assertThat(invalid).containsExactly(MarketField.PRICE);
    }

    private static MarketValue.Known known(MarketValue value) {
        assertThat(value).isInstanceOf(MarketValue.Known.class);
        return (MarketValue.Known) value;
    }
}
