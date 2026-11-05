package com.memetrade.bots.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.BotConfigValidator.RawLimits;
import com.memetrade.bots.domain.BotConfigValidator.RawRule;
import com.memetrade.bots.domain.BotConfigValidator.RawSelection;
import com.memetrade.bots.domain.BotConfigValidator.RawToken;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Written BEFORE {@link BotConfigValidator} and {@link DefaultLimits} (Passo 6; US4.1, US4.2, US4.9; BR1.2 to BR1.6,
 * BR5.1; NFR6.2). Inclusive boundaries: min and max accepted, one step outside refused; the same rules the screen
 * repeats. Synthetic Base58 mints only.
 */
@Tag("u5")
class BotConfigValidatorTest {

    private static final String MINT_A = "So1aNaTestMintAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MINT_B = "So1aNaTestMintBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    private final BotConfigValidator validator =
            new BotConfigValidator(new BigDecimal("100.000000"), new BigDecimal("10000.000000"));

    /** AC4.1.2: min and max accepted; min minus a step, max plus a step and a non-number refused in the field. */
    @Test
    void aParameterAcceptsItsInclusiveRangeOnItsStep() {
        StrategyTemplate momentum = StrategyTemplates.MOMENTUM_VOLUME_V1;

        assertThat(validator.parameters(momentum, with(momentum, "windowMinutes", "10")))
                .isEmpty();
        assertThat(validator.parameters(momentum, with(momentum, "windowMinutes", "60")))
                .isEmpty();
        assertThat(fields(validator.parameters(momentum, with(momentum, "windowMinutes", "5"))))
                .containsExactly("parameters.windowMinutes");
        assertThat(fields(validator.parameters(momentum, with(momentum, "windowMinutes", "65"))))
                .containsExactly("parameters.windowMinutes");
        assertThat(fields(validator.parameters(momentum, with(momentum, "windowMinutes", "quinze"))))
                .containsExactly("parameters.windowMinutes");
        assertThat(fields(validator.parameters(momentum, with(momentum, "windowMinutes", "12"))))
                .containsExactly("parameters.windowMinutes");
    }

    /** BR1.2 with decimal steps: 0,5 and 20 accepted for the price rise, 0,75 is off the 0,5 grid. */
    @Test
    void decimalStepsAreExact() {
        StrategyTemplate momentum = StrategyTemplates.MOMENTUM_VOLUME_V1;
        StrategyTemplate range = StrategyTemplates.RANGE_BREAKOUT_V1;

        assertThat(validator.parameters(momentum, with(momentum, "priceRiseMinPct", "0.5")))
                .isEmpty();
        assertThat(validator.parameters(momentum, with(momentum, "priceRiseMinPct", "20.0000")))
                .isEmpty();
        assertThat(validator.parameters(momentum, with(momentum, "priceRiseMinPct", "0.75")))
                .hasSize(1);
        assertThat(validator.parameters(range, with(range, "breakoutMarginPct", "0.1")))
                .isEmpty();
        assertThat(validator.parameters(range, with(range, "breakoutMarginPct", "0.15")))
                .hasSize(1);
    }

    /** NFR6.2: an unknown parameter or a missing one is refused. */
    @Test
    void unknownAndMissingParametersAreRefused() {
        StrategyTemplate momentum = StrategyTemplates.MOMENTUM_VOLUME_V1;
        Map<String, String> unknown = with(momentum, "leverage", "2");
        Map<String, String> missing = with(momentum, "windowMinutes", "15");
        missing.remove("stopLossPct");

        assertThat(fields(validator.parameters(momentum, unknown))).containsExactly("parameters.leverage");
        assertThat(fields(validator.parameters(momentum, missing))).containsExactly("parameters.stopLossPct");
    }

    /** AC4.1.5, BR1.3: a selection without token and without rule does not advance, with the reason. */
    @Test
    void aSelectionNeedsATokenOrARule() {
        List<Violation> empty = validator.selection(new RawSelection(List.of(), List.of()));

        assertThat(fields(empty)).containsExactly("tokenSelection");
        assertThat(empty.getFirst().message()).contains("token").contains("regra");
        assertThat(validator.selection(new RawSelection(List.of(new RawToken("solana", MINT_A)), List.of())))
                .isEmpty();
        assertThat(validator.selection(new RawSelection(List.of(), List.of(new RawRule("LIQUIDITY", "GTE", "10000")))))
                .isEmpty();
    }

    /** BR1.3: up to 10 tokens without repetition, valid Base58 mints; up to 5 rules of the closed fields. */
    @Test
    void theSelectionLimitsAndFormatsAreChecked() {
        List<RawToken> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(
                    new RawToken("solana", "So1aNaTestMint" + "ABCDEFGHJKL".charAt(i) + "AAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        }
        List<RawRule> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new RawRule("HOLDERS", "GTE", String.valueOf(i)));
        }

        assertThat(fields(validator.selection(new RawSelection(eleven, List.of()))))
                .containsExactly("tokenSelection.tokens");
        assertThat(fields(validator.selection(new RawSelection(
                        List.of(new RawToken("solana", MINT_A), new RawToken("solana", MINT_A)), List.of()))))
                .containsExactly("tokenSelection.tokens[1]");
        assertThat(fields(validator.selection(
                        new RawSelection(List.of(new RawToken("solana", "0OIl-not-base58")), List.of()))))
                .containsExactly("tokenSelection.tokens[0].mint");
        assertThat(fields(validator.selection(new RawSelection(List.of(), six))))
                .containsExactly("tokenSelection.rules");
        assertThat(fields(validator.selection(new RawSelection(
                        List.of(new RawToken("solana", MINT_B)),
                        List.of(
                                new RawRule("PRICE", "GTE", "1"),
                                new RawRule("HOLDERS", "EQ", "1"),
                                new RawRule("HOLDERS", "GTE", "-1"))))))
                .containsExactly(
                        "tokenSelection.rules[0].field",
                        "tokenSelection.rules[1].operator",
                        "tokenSelection.rules[2].value");
    }

    /** AC4.2.4: the cap S is accepted; S plus the smallest increment, zero and a negative value are refused. */
    @Test
    void theVirtualBalanceAcceptsTheCapAndRefusesAboveItShowingTheCap() {
        assertThat(validator.balance("10000.000000")).isEmpty();
        assertThat(validator.balance("100")).isEmpty();
        List<Violation> above = validator.balance("10000.000001");

        assertThat(above).extracting(Violation::code).containsExactly(Violation.Code.VIRTUAL_BALANCE_ABOVE_CAP);
        assertThat(above.getFirst().message()).contains("10.000,00");
        assertThat(validator.balance("0"))
                .extracting(Violation::code)
                .containsExactly(Violation.Code.VALIDATION_FAILED);
        assertThat(validator.balance("-1"))
                .extracting(Violation::code)
                .containsExactly(Violation.Code.VALIDATION_FAILED);
        assertThat(validator.balance("99.999999")).hasSize(1);
        assertThat(validator.balance("mil")).hasSize(1);
        assertThat(validator.balance("100.0000001")).hasSize(1);
    }

    /** AC4.2.2: token exposure equal to the total is accepted; the total plus 0,000001 is LIMIT_INCOHERENT. */
    @Test
    void theTokenExposureMayEqualTheTotalButNotExceedIt() {
        assertThat(validator.limits(limits("100", "500.000000", "500.000000"))).isEmpty();
        List<Violation> above = validator.limits(limits("100", "500.000001", "500.000000"));

        assertThat(above).extracting(Violation::field).containsExactly("maxTokenExposure");
        assertThat(above).extracting(Violation::code).containsExactly(Violation.Code.LIMIT_INCOHERENT);
    }

    /** AC4.2.3: order above token exposure, zero or negative values and slippage above 100% are refused per field. */
    @Test
    void incoherentZeroNegativeAndOutOfRangeLimitsAreRefusedPerField() {
        assertThat(validator.limits(limits("200.000001", "200", "500")))
                .extracting(Violation::field, Violation::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("maxOrderSize", Violation.Code.LIMIT_INCOHERENT));
        RawLimits bad = new RawLimits("0", "-1", "500", "0", "0", "100.0001", "-0.000001", 0L);

        assertThat(fields(validator.limits(bad)))
                .containsExactlyInAnyOrder(
                        "maxOrderSize",
                        "maxTokenExposure",
                        "maxDailyLoss",
                        "maxDrawdown",
                        "maxSlippage",
                        "minLiquidity",
                        "minIntervalSeconds");
        assertThat(validator.limits(new RawLimits("100", "200", "500", "50", "100", "100", "0", 1L)))
                .isEmpty();
    }

    /** NFR6.2: a missing limit or a non-decimal text is refused, never read as zero. */
    @Test
    void missingAndNonDecimalLimitsAreRefused() {
        RawLimits missing = new RawLimits(null, "200", "500", "50", "10", "2", "10000", null);
        RawLimits text = new RawLimits("cem", "200", "500", "50", "10", "2", "10000", 240L);

        assertThat(fields(validator.limits(missing))).containsExactlyInAnyOrder("maxOrderSize", "minIntervalSeconds");
        assertThat(fields(validator.limits(text))).containsExactly("maxOrderSize");
    }

    /** BR1.6, Q8, Q13: the defaults are proportional to the balance, with 240 s between operations. */
    @Test
    void theDefaultLimitsAreProportionalToTheBalance() {
        BotLimits defaults = DefaultLimits.forBalance(new BigDecimal("1000.000000"));

        assertThat(defaults.maxOrderSize()).isEqualTo(new BigDecimal("100.000000"));
        assertThat(defaults.maxTokenExposure()).isEqualTo(new BigDecimal("200.000000"));
        assertThat(defaults.maxTotalExposure()).isEqualTo(new BigDecimal("500.000000"));
        assertThat(defaults.maxDailyLoss()).isEqualTo(new BigDecimal("50.000000"));
        assertThat(defaults.maxDrawdownPct()).isEqualTo(new BigDecimal("10.0000"));
        assertThat(defaults.maxSlippagePct()).isEqualTo(new BigDecimal("2.0000"));
        assertThat(defaults.minLiquidity()).isEqualTo(new BigDecimal("10000.000000"));
        assertThat(defaults.minIntervalSeconds()).isEqualTo(240L);
    }

    /** BR1.6: the rounding is USDC with 6 places, HALF_EVEN, once; the defaults are always coherent. */
    @Test
    void theDefaultLimitsRoundHalfEvenToSixPlacesAndStayCoherent() {
        BotLimits defaults = DefaultLimits.forBalance(new BigDecimal("333.333333"));

        assertThat(defaults.maxOrderSize()).isEqualTo(new BigDecimal("33.333333"));
        assertThat(defaults.maxTokenExposure()).isEqualTo(new BigDecimal("66.666667"));
        assertThat(defaults.maxTotalExposure()).isEqualTo(new BigDecimal("166.666666"));
        assertThat(defaults.maxDailyLoss()).isEqualTo(new BigDecimal("16.666667"));
        for (String balance : List.of("100", "1000", "7777.777777", "10000")) {
            BotLimits limits = DefaultLimits.forBalance(new BigDecimal(balance));
            assertThat(validator.limits(raw(limits))).as(balance).isEmpty();
        }
    }

    // ----- helpers -----

    private static Map<String, String> with(StrategyTemplate template, String key, String value) {
        Map<String, String> raw = new LinkedHashMap<>();
        template.parameters()
                .forEach(spec -> raw.put(spec.key(), spec.defaultValue().toPlainString()));
        raw.put(key, value);
        return raw;
    }

    private static RawLimits limits(String order, String token, String total) {
        return new RawLimits(order, token, total, "50", "10", "2", "10000", 240L);
    }

    private static RawLimits raw(BotLimits limits) {
        return new RawLimits(
                limits.maxOrderSize().toPlainString(),
                limits.maxTokenExposure().toPlainString(),
                limits.maxTotalExposure().toPlainString(),
                limits.maxDailyLoss().toPlainString(),
                limits.maxDrawdownPct().toPlainString(),
                limits.maxSlippagePct().toPlainString(),
                limits.minLiquidity().toPlainString(),
                limits.minIntervalSeconds());
    }

    private static List<String> fields(List<Violation> violations) {
        return violations.stream().map(Violation::field).toList();
    }
}
