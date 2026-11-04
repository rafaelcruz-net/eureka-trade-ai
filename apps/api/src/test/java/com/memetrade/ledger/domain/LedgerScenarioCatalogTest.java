package com.memetrade.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The catalogue of known-result scenarios C1 to C15 of functional-spec.md (BR6.7), written BEFORE the code from the
 * table, every number compared by EXACT equality (value and scale, as text). Policy v1; mint with 6 decimals unless
 * said; balance 1000.000000 unless said.
 */
@Tag("u7")
class LedgerScenarioCatalogTest {

    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final SourceRef SOURCE = new SourceRef("sim-u7", "Simulado U7", true);
    static final String BALANCE = "1000.000000";

    final FillCalculator calculator = new FillCalculator(SimulationPolicy.V1);

    static MarketInputs market(String price, String liquidity, int decimals) {
        return new MarketInputs(known(price), known(liquidity), known(String.valueOf(decimals)), NOW);
    }

    static MarketValue.Known known(String value) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, NOW, false);
    }

    static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    FillQuote.Computed buy(String quantity, String price, String liquidity, String balance, int decimals) {
        return (FillQuote.Computed) calculator.quote(
                OrderSide.BUY, d(quantity), BigDecimal.ZERO, d(balance), market(price, liquidity, decimals));
    }

    FillQuote.Computed sell(String quantity, String position, String price, String liquidity) {
        return (FillQuote.Computed)
                calculator.quote(OrderSide.SELL, d(quantity), d(position), d(BALANCE), market(price, liquidity, 6));
    }

    @Nested
    class Fills {

        @Test
        void c1BuyAndSell() {
            FillQuote.Computed buy = buy("100", "1.000000", "100000.000000", BALANCE, 6);
            assertThat(buy.consumedPct()).isEqualTo(d("0.1000"));
            assertThat(buy.slippagePct()).isEqualTo(d("0.6000"));
            assertThat(buy.fillPrice()).isEqualTo(d("1.006000000000"));
            assertThat(buy.grossAmount()).isEqualTo(d("100.600000"));
            assertThat(buy.referenceAmount()).isEqualTo(d("100.000000"));
            assertThat(buy.fee()).isEqualTo(d("0.301800"));
            assertThat(buy.quotedPrice()).isEqualTo(d("1.005000000000"));
            assertThat(buy.spreadPct()).isEqualTo(d("0.5000"));
            assertThat(buy.impactPct()).isEqualTo(d("0.1000"));
            assertThat(buy.partial()).isFalse();

            FillQuote.Computed sell = sell("100", "100", "1.500000", "100000.000000");
            assertThat(sell.consumedPct()).isEqualTo(d("0.1500"));
            assertThat(sell.slippagePct()).isEqualTo(d("0.6500"));
            assertThat(sell.fillPrice()).isEqualTo(d("1.490250000000"));
            assertThat(sell.grossAmount()).isEqualTo(d("149.025000"));
            assertThat(sell.referenceAmount()).isEqualTo(d("150.000000"));
            assertThat(sell.fee()).isEqualTo(d("0.447075"));
        }

        @Test
        void c2SellAtALoss() {
            FillQuote.Computed sell = sell("100", "100", "0.800000", "100000.000000");
            assertThat(sell.consumedPct()).isEqualTo(d("0.0800"));
            assertThat(sell.slippagePct()).isEqualTo(d("0.5800"));
            assertThat(sell.fillPrice()).isEqualTo(d("0.795360000000"));
            assertThat(sell.grossAmount()).isEqualTo(d("79.536000"));
            assertThat(sell.referenceAmount()).isEqualTo(d("80.000000"));
            assertThat(sell.fee()).isEqualTo(d("0.238608"));
        }

        @Test
        void c3SecondBuyAndPartialSell() {
            FillQuote.Computed second = buy("100", "2.000000", "100000.000000", BALANCE, 6);
            assertThat(second.consumedPct()).isEqualTo(d("0.2000"));
            assertThat(second.slippagePct()).isEqualTo(d("0.7000"));
            assertThat(second.fillPrice()).isEqualTo(d("2.014000000000"));
            assertThat(second.grossAmount()).isEqualTo(d("201.400000"));
            assertThat(second.fee()).isEqualTo(d("0.604200"));

            FillQuote.Computed sell = sell("50", "200", "3.000000", "100000.000000");
            assertThat(sell.consumedPct()).isEqualTo(d("0.1500"));
            assertThat(sell.fillPrice()).isEqualTo(d("2.980500000000"));
            assertThat(sell.grossAmount()).isEqualTo(d("149.025000"));
            assertThat(sell.fee()).isEqualTo(d("0.447075"));
            assertThat(sell.partial()).isFalse();
        }

        @Test
        void c5aTokenWithSixDecimalsTruncatesWithoutBeingPartial() {
            FillQuote.Computed buy = buy("1.123456789", "2.000000", "1000000.000000", BALANCE, 6);
            assertThat(buy.requestedQuantity()).isEqualTo(d("1.123456789"));
            assertThat(buy.filledQuantity()).isEqualTo(d("1.123456"));
            assertThat(buy.consumedPct()).isEqualTo(d("0.0002"));
            assertThat(buy.slippagePct()).isEqualTo(d("0.5002"));
            assertThat(buy.fillPrice()).isEqualTo(d("2.010004000000"));
            assertThat(buy.referenceAmount()).isEqualTo(d("2.246912"));
            assertThat(buy.grossAmount()).isEqualTo(d("2.258151"));
            assertThat(buy.grossAmount().subtract(buy.referenceAmount())).isEqualTo(d("0.011239"));
            assertThat(buy.fee()).isEqualTo(d("0.006774"));
            assertThat(buy.partial())
                    .as("the truncation alone is not a partial fill")
                    .isFalse();
        }

        @Test
        void c5bTokenWithNineDecimals() {
            FillQuote.Computed buy = buy("1.123456789", "2.000000", "1000000.000000", BALANCE, 9);
            assertThat(buy.filledQuantity()).isEqualTo(d("1.123456789"));
            assertThat(buy.consumedPct()).isEqualTo(d("0.0002"));
            assertThat(buy.fillPrice()).isEqualTo(d("2.010004000000"));
            assertThat(buy.referenceAmount()).isEqualTo(d("2.246914"));
            assertThat(buy.grossAmount()).isEqualTo(d("2.258153"));
            assertThat(buy.grossAmount().subtract(buy.referenceAmount())).isEqualTo(d("0.011239"));
            assertThat(buy.fee()).isEqualTo(d("0.006774"));
        }

        @Test
        void c6aTheMidpointRoundsToTheEvenNeighbourBelow() {
            FillQuote.Computed buy = buy("0.750000", "2.000000", "1000000000.000000", BALANCE, 6);
            assertThat(buy.consumedPct()).isEqualTo(d("0.0000"));
            assertThat(buy.slippagePct()).isEqualTo(d("0.5000"));
            assertThat(buy.fillPrice()).isEqualTo(d("2.010000000000"));
            assertThat(buy.grossAmount()).isEqualTo(d("1.507500"));
            assertThat(buy.fee()).as("exact 0.0045225").isEqualTo(d("0.004522"));
            assertThat(buy.referenceAmount()).isEqualTo(d("1.500000"));
        }

        @Test
        void c6bTheMidpointRoundsToTheEvenNeighbourAbove() {
            FillQuote.Computed buy = buy("0.250000", "2.000000", "1000000000.000000", BALANCE, 6);
            assertThat(buy.grossAmount()).isEqualTo(d("0.502500"));
            assertThat(buy.fee()).as("exact 0.0015075").isEqualTo(d("0.001508"));
            assertThat(buy.referenceAmount()).isEqualTo(d("0.500000"));
        }

        @Test
        void c7SellAtTheSamePriceAndBuyAgain() {
            FillQuote.Computed sell = sell("100", "100", "1.000000", "100000.000000");
            assertThat(sell.fillPrice()).isEqualTo(d("0.994000000000"));
            assertThat(sell.grossAmount()).isEqualTo(d("99.400000"));
            assertThat(sell.fee()).isEqualTo(d("0.298200"));

            FillQuote.Computed buy = buy("10", "4.000000", "100000.000000", BALANCE, 6);
            assertThat(buy.consumedPct()).isEqualTo(d("0.0400"));
            assertThat(buy.slippagePct()).isEqualTo(d("0.5400"));
            assertThat(buy.fillPrice()).isEqualTo(d("4.021600000000"));
            assertThat(buy.grossAmount()).isEqualTo(d("40.216000"));
            assertThat(buy.fee()).isEqualTo(d("0.120648"));
        }

        @Test
        void c10PartialFillByTheLiquidityCap() {
            FillQuote.Computed buy = buy("2000", "1.000000", "10000.000000", "2000.000000", 6);
            assertThat(buy.filledQuantity()).isEqualTo(d("1000.000000"));
            assertThat(buy.requestedQuantity()).isEqualTo(d("2000"));
            assertThat(buy.partial()).isTrue();
            assertThat(buy.consumedPct()).isEqualTo(d("10.0000"));
            assertThat(buy.slippagePct()).isEqualTo(d("10.5000"));
            assertThat(buy.fillPrice()).isEqualTo(d("1.105000000000"));
            assertThat(buy.referenceAmount()).isEqualTo(d("1000.000000"));
            assertThat(buy.grossAmount()).isEqualTo(d("1105.000000"));
            assertThat(buy.fee()).isEqualTo(d("3.315000"));
        }

        @Test
        void c11SellAboveThePositionIsLimitedToThePosition() {
            FillQuote.Computed sell = sell("150", "100", "1.500000", "100000.000000");
            assertThat(sell.filledQuantity()).isEqualTo(d("100.000000"));
            assertThat(sell.requestedQuantity()).isEqualTo(d("150"));
            assertThat(sell.partial()).isTrue();
            assertThat(sell.grossAmount()).isEqualTo(d("149.025000"));
            assertThat(sell.fee()).isEqualTo(d("0.447075"));
        }

        @Test
        void c12CloseAtTheReferencePriceWithoutSpreadImpactNorCap() {
            FillQuote.Computed close =
                    (FillQuote.Computed) calculator.closeQuote(d("100"), market("1.200000", "100000.000000", 6));
            assertThat(close.grossAmount()).isEqualTo(d("120.000000"));
            assertThat(close.referenceAmount()).isEqualTo(d("120.000000"));
            assertThat(close.fee()).isEqualTo(d("0.360000"));
            assertThat(close.spreadPct()).isEqualTo(d("0.0000"));
            assertThat(close.impactPct()).isEqualTo(d("0.0000"));
            assertThat(close.consumedPct()).isEqualTo(d("0.0000"));
            assertThat(close.quotedPrice()).isEqualTo(d("1.200000000000"));
            assertThat(close.fillPrice()).isEqualTo(d("1.200000000000"));
        }

        @Test
        void c14AVeryLowPriceKeepsSpreadAndImpactWithTwelveDecimals() {
            FillQuote.Computed buy = buy("50000000", "0.000002", "1000000.000000", BALANCE, 6);
            assertThat(buy.consumedPct()).isEqualTo(d("0.0100"));
            assertThat(buy.slippagePct()).isEqualTo(d("0.5100"));
            assertThat(buy.fillPrice()).isEqualTo(d("0.000002010200"));
            assertThat(buy.grossAmount()).isEqualTo(d("100.510000"));
            assertThat(buy.referenceAmount()).isEqualTo(d("100.000000"));
            assertThat(buy.fee()).isEqualTo(d("0.301530"));
        }

        @Test
        void c15TheRealCostAboveTheBalanceIsInsufficientBalance() {
            FillQuote quote = calculator.quote(
                    OrderSide.BUY,
                    d("99.600000"),
                    BigDecimal.ZERO,
                    d("100.000000"),
                    market("1.000000", "100000.000000", 6));
            assertThat(quote).isEqualTo(new FillQuote.Refused(NotFilledReason.INSUFFICIENT_BALANCE));

            FillQuote.Computed atBalance = buy("99.600000", "1.000000", "100000.000000", "100.497794", 6);
            assertThat(atBalance.consumedPct()).isEqualTo(d("0.0996"));
            assertThat(atBalance.slippagePct()).isEqualTo(d("0.5996"));
            assertThat(atBalance.fillPrice()).isEqualTo(d("1.005996000000"));
            assertThat(atBalance.grossAmount()).isEqualTo(d("100.197202"));
            assertThat(atBalance.fee()).isEqualTo(d("0.300592"));
            assertThat(atBalance.grossAmount().add(atBalance.fee())).isEqualTo(d("100.497794"));
        }
    }

    @Nested
    class Refusals {

        @Test
        void br12WithoutAKnownPriceThereIsNoFill() {
            MarketInputs noPrice = new MarketInputs(
                    new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty()),
                    known("100000"),
                    known("6"),
                    NOW);
            assertThat(calculator.quote(OrderSide.BUY, d("1"), BigDecimal.ZERO, d(BALANCE), noPrice))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.PRICE_UNAVAILABLE));
        }

        @Test
        void br12APriceObservedFiveMinutesAndOneSecondAgoIsStale() {
            MarketValue.Known old = new MarketValue.Known(d("1"), SOURCE, NOW.minus(Duration.ofSeconds(301)), false);
            MarketValue.Known fresh = new MarketValue.Known(d("1"), SOURCE, NOW.minus(Duration.ofSeconds(300)), false);
            MarketValue.Known flagged = new MarketValue.Known(d("1"), SOURCE, NOW, true);

            assertThat(calculator.quote(
                            OrderSide.BUY,
                            d("1"),
                            BigDecimal.ZERO,
                            d(BALANCE),
                            new MarketInputs(old, known("100000"), known("6"), NOW)))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.PRICE_UNAVAILABLE));
            assertThat(calculator.quote(
                            OrderSide.BUY,
                            d("1"),
                            BigDecimal.ZERO,
                            d(BALANCE),
                            new MarketInputs(flagged, known("100000"), known("6"), NOW)))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.PRICE_UNAVAILABLE));
            assertThat(calculator.quote(
                            OrderSide.BUY,
                            d("1"),
                            BigDecimal.ZERO,
                            d(BALANCE),
                            new MarketInputs(fresh, known("100000"), known("6"), NOW)))
                    .isInstanceOf(FillQuote.Computed.class);
        }

        @Test
        void br12ZeroOrUnknownLiquidityIsNoLiquidity() {
            assertThat(calculator.quote(OrderSide.BUY, d("1"), BigDecimal.ZERO, d(BALANCE), market("1", "0", 6)))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.NO_LIQUIDITY));
            MarketInputs unknownLiquidity = new MarketInputs(
                    known("1"),
                    new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)),
                    known("6"),
                    NOW);
            assertThat(calculator.quote(OrderSide.BUY, d("1"), BigDecimal.ZERO, d(BALANCE), unknownLiquidity))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.NO_LIQUIDITY));
        }

        @Test
        void br17UnknownDecimalsArePriceUnavailable() {
            MarketInputs unknownDecimals = new MarketInputs(
                    known("1"),
                    known("100000"),
                    new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()),
                    NOW);
            assertThat(calculator.quote(OrderSide.BUY, d("1"), BigDecimal.ZERO, d(BALANCE), unknownDecimals))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.PRICE_UNAVAILABLE));
        }

        @Test
        void br14TheCapTruncatedToZeroIsBelowTheMinimum() {
            // 10% of 0.000001 USDC of liquidity at 1.0 is 0.0000001 token: truncated to 6 decimals it is zero.
            assertThat(calculator.quote(
                            OrderSide.BUY, d("1"), BigDecimal.ZERO, d(BALANCE), market("1.000000", "0.000001", 6)))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.QUANTITY_BELOW_MINIMUM));
            assertThat(calculator.quote(
                            OrderSide.BUY,
                            d("0.0000001"),
                            BigDecimal.ZERO,
                            d(BALANCE),
                            market("1.000000", "100000.000000", 6)))
                    .as("the request itself truncates to zero")
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.QUANTITY_BELOW_MINIMUM));
        }

        @Test
        void br14TheCapIsExactlyTenPercentOfTheLiquidity() {
            FillQuote.Computed atCap = buy("1000", "1.000000", "10000.000000", "2000.000000", 6);
            assertThat(atCap.partial()).isFalse();
            assertThat(atCap.consumedPct()).isEqualTo(d("10.0000"));
        }

        @Test
        void br15ASellWithoutPositionIsNoPosition() {
            assertThat(calculator.quote(
                            OrderSide.SELL,
                            d("10"),
                            BigDecimal.ZERO,
                            d(BALANCE),
                            market("1.000000", "100000.000000", 6)))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.NO_POSITION));
        }

        @Test
        void aCloseWithoutAReliablePriceIsNotComputed() {
            MarketInputs stale = new MarketInputs(
                    new MarketValue.Known(d("1.2"), SOURCE, NOW.minus(Duration.ofMinutes(6)), true),
                    known("100000"),
                    known("6"),
                    NOW);
            assertThat(calculator.closeQuote(d("100"), stale))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.PRICE_UNAVAILABLE));
        }

        @Test
        void aBuyWhoseGrossRoundsToZeroIsBelowTheMinimum() {
            assertThat(calculator.quote(
                            OrderSide.BUY,
                            d("0.000001"),
                            BigDecimal.ZERO,
                            d(BALANCE),
                            market("0.000002", "100000.000000", 6)))
                    .isEqualTo(new FillQuote.Refused(NotFilledReason.QUANTITY_BELOW_MINIMUM));
        }
    }
}
