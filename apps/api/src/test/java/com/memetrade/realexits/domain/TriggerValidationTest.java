package com.memetrade.realexits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U7, written BEFORE the code, from BR3.1 to BR3.4, BR2.6 and the scenarios S1, S3, S4, S5 and S43: the
 * prices of a conditional against the current price in USD, in exact decimals, before any call to the Jupiter or the
 * TxSigner (U7-NFR1.4). The first refusal in the order of BR3.4 wins.
 */
@Tag("u7-exits-and-fiscal")
class TriggerValidationTest {

    private static final CurrentPrice PRICE = CurrentPrice.known(new BigDecimal("0.000100"));

    private static Optional<TriggerRefusal> check(ConditionalKind kind, String stop, String takeProfit) {
        return TriggerValidation.check(
                kind,
                Optional.ofNullable(stop).map(BigDecimal::new),
                Optional.ofNullable(takeProfit).map(BigDecimal::new),
                PRICE);
    }

    /** S1: an OCO with the stop below and the take-profit above the current price. */
    @Test
    void anOcoWithTheStopBelowAndTheTakeProfitAboveIsAccepted() {
        assertThat(check(ConditionalKind.OCO, "0.00008", "0.00015")).isEmpty();
        assertThat(check(ConditionalKind.STOP, "0.00009", null)).isEmpty();
        assertThat(check(ConditionalKind.TAKE_PROFIT, null, "0.00011")).isEmpty();
    }

    /** S3, BR3.3: without a current price in USD nothing else is checked. */
    @Test
    void anUnknownCurrentPriceRefusesTheCreation() {
        assertThat(TriggerValidation.check(
                        ConditionalKind.OCO,
                        Optional.of(new BigDecimal("0.00008")),
                        Optional.of(new BigDecimal("0.00015")),
                        CurrentPrice.unknown()))
                .contains(TriggerRefusal.PRICE_UNKNOWN);
    }

    /** S4, BR3.4: in an OCO the take-profit must be above the stop. */
    @Test
    void anOcoWhoseTakeProfitIsNotAboveTheStopIsRefused() {
        assertThat(check(ConditionalKind.OCO, "0.00009", "0.00008")).contains(TriggerRefusal.TP_NOT_ABOVE_SL);
        assertThat(check(ConditionalKind.OCO, "0.00009", "0.00009")).contains(TriggerRefusal.TP_NOT_ABOVE_SL);
    }

    /** S5, BR3.4: the stop below the current price and the take-profit above it. */
    @Test
    void aTriggerOnTheWrongSideOfTheCurrentPriceIsRefused() {
        assertThat(check(ConditionalKind.STOP, "0.00011", null)).contains(TriggerRefusal.TRIGGER_ON_WRONG_SIDE);
        assertThat(check(ConditionalKind.STOP, "0.000100", null))
                .as("equal to the current price is not below it")
                .contains(TriggerRefusal.TRIGGER_ON_WRONG_SIDE);
        assertThat(check(ConditionalKind.TAKE_PROFIT, null, "0.00009")).contains(TriggerRefusal.TRIGGER_ON_WRONG_SIDE);
        assertThat(check(ConditionalKind.OCO, "0.00011", "0.00015")).contains(TriggerRefusal.TRIGGER_ON_WRONG_SIDE);
    }

    /** S43, BR2.6: a stop at 40% of the current price or a take-profit at 12 times it. */
    @Test
    void aTriggerOutOfTheBoundsOfTheApiIsRefused() {
        assertThat(check(ConditionalKind.STOP, "0.00004", null)).contains(TriggerRefusal.TRIGGER_OUT_OF_BOUNDS);
        assertThat(check(ConditionalKind.TAKE_PROFIT, null, "0.0012")).contains(TriggerRefusal.TRIGGER_OUT_OF_BOUNDS);
        assertThat(check(ConditionalKind.OCO, "0.00004", "0.00015")).contains(TriggerRefusal.TRIGGER_OUT_OF_BOUNDS);
    }

    /** BR2.6: the bounds themselves are accepted (50% exactly and 10 times exactly). */
    @Test
    void theBoundsThemselvesAreAccepted() {
        assertThat(check(ConditionalKind.STOP, "0.00005", null)).isEmpty();
        assertThat(check(ConditionalKind.TAKE_PROFIT, null, "0.001")).isEmpty();
        assertThat(check(ConditionalKind.STOP, "0.0000499999", null)).contains(TriggerRefusal.TRIGGER_OUT_OF_BOUNDS);
        assertThat(check(ConditionalKind.TAKE_PROFIT, null, "0.0010000001"))
                .contains(TriggerRefusal.TRIGGER_OUT_OF_BOUNDS);
    }

    /** BR3.1: each kind carries exactly its prices. */
    @Test
    void theKindCarriesExactlyItsPrices() {
        assertThat(check(ConditionalKind.STOP, null, null)).contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.STOP, "0.00009", "0.00011")).contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.TAKE_PROFIT, "0.00009", "0.00011")).contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.OCO, "0.00009", null)).contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.OCO, null, "0.00011")).contains(TriggerRefusal.VALIDATION_FAILED);
    }

    /** BR3.2: a price is positive and fits numeric(38,18): at most 18 decimal places. */
    @Test
    void aPriceIsPositiveWithAtMostEighteenDecimals() {
        assertThat(check(ConditionalKind.STOP, "0", null)).contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.STOP, "-0.00009", null)).contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.STOP, "0.0000900000000000001", null))
                .contains(TriggerRefusal.VALIDATION_FAILED);
        assertThat(check(ConditionalKind.STOP, "0.000090000000000000", null)).isEmpty();
    }

    /** BR3.4: the order of the checks; the ordering of the OCO is checked before the side of each price. */
    @Test
    void theOrderOfTheChecksIsTheOneOfTheRules() {
        assertThat(check(ConditionalKind.OCO, "0.0002", "0.00003"))
                .as("take-profit below the stop and both on the wrong side")
                .contains(TriggerRefusal.TP_NOT_ABOVE_SL);
        assertThat(check(ConditionalKind.OCO, "0.00011", "0.0012"))
                .as("wrong side comes before the bounds")
                .contains(TriggerRefusal.TRIGGER_ON_WRONG_SIDE);
    }

    /** BR2.6: the floor of the TxSigner when the instruction has a minimum output (amount x stop x (1 - slippage)). */
    @Test
    void theFloorOfTheMinimumOutputIsTheStopValueLessTheSlippage() {
        assertThat(TriggerValidation.minOutputFloorUsd(new BigDecimal("1000000"), new BigDecimal("0.00008"), 200))
                .isEqualByComparingTo("78.4");
    }

    /** The current price of a position is known or unknown, never zero (project.md). */
    @Test
    void aCurrentPriceOfZeroIsUnknown() {
        assertThat(CurrentPrice.known(BigDecimal.ZERO).isKnown()).isFalse();
        assertThat(CurrentPrice.known(new BigDecimal("-1")).isKnown()).isFalse();
        assertThat(PRICE.isKnown()).isTrue();
    }
}
