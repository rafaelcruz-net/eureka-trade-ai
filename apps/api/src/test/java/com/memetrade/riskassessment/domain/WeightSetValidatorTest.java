package com.memetrade.riskassessment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskassessment.api.CurvePoint;
import com.memetrade.riskassessment.api.FactorDefinition;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.api.WeightSetDraft;
import com.memetrade.riskassessment.domain.WeightSetValidator.Violation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** BR4.1: each rule of a weight version, starting from the valid version 1 and changing one field at a time. */
@Tag("u4")
class WeightSetValidatorTest {

    private final WeightSetDraft valid = WeightsV1.draft();

    @Test
    void versionOneIsValid() {
        assertThat(WeightSetValidator.validate(valid)).isEmpty();
    }

    @Test
    void theWeightsMustAddUpToExactlyOneHundred() {
        assertThat(fields(withWeight(0, "35.0001"))).containsExactly("factors");
        assertThat(fields(withWeight(0, "34.9999"))).containsExactly("factors");
    }

    @Test
    void aWeightIsPositiveWithAtMostFourDecimals() {
        assertThat(fields(withWeight(1, "0"))).contains("factors[1].weight");
        assertThat(fields(withWeight(1, "25.00001"))).contains("factors[1].weight");
    }

    @Test
    void exactlyTheFourFactorsOnceEach() {
        List<FactorDefinition> three = valid.factors().subList(0, 3);
        List<FactorDefinition> repeated = new ArrayList<>(valid.factors());
        repeated.set(
                3,
                new FactorDefinition(
                        FactorId.LIQUIDITY,
                        new BigDecimal("20.0000"),
                        valid.factors().get(3).curve()));

        assertThat(fields(draft(three))).containsExactly("factors");
        assertThat(fields(draft(repeated))).contains("factors[3].factorId");
    }

    @Test
    void aCurveHasTwoToEightIncreasingPointsWithRisksFromZeroToOneHundred() {
        assertThat(fields(withCurve(List.of(point("1", 50))))).contains("factors[0].curve");
        List<CurvePoint> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(point(Integer.toString(i), 10));
        }
        assertThat(fields(withCurve(nine))).contains("factors[0].curve");
        assertThat(fields(withCurve(List.of(point("10", 50), point("10", 40))))).contains("factors[0].curve");
        assertThat(fields(withCurve(List.of(point("-1", 50), point("10", 40))))).contains("factors[0].curve");
        assertThat(fields(withCurve(List.of(point("1", 50), point("10", 101))))).contains("factors[0].curve");
        assertThat(fields(withCurve(List.of(point("1", 0), point("10", 100))))).isEmpty();
    }

    @Test
    void theBandsMustBeOrderedBelowOneHundred() {
        assertThat(fields(new WeightSetDraft(valid.factors(), 69, 39, valid.minCoverage())))
                .contains("lowMax");
        assertThat(fields(new WeightSetDraft(valid.factors(), 39, 100, valid.minCoverage())))
                .contains("lowMax");
        assertThat(fields(new WeightSetDraft(valid.factors(), -1, 69, valid.minCoverage())))
                .contains("lowMax");
        assertThat(fields(new WeightSetDraft(valid.factors(), 0, 1, valid.minCoverage())))
                .isEmpty();
    }

    @Test
    void theMinimumCoverageIsAboveZeroAndAtMostOneHundred() {
        assertThat(fields(coverage("0"))).contains("minCoverage");
        assertThat(fields(coverage("100.0001"))).contains("minCoverage");
        assertThat(fields(coverage("50.12345"))).contains("minCoverage");
        assertThat(fields(coverage("100.0000"))).isEmpty();
        assertThat(fields(coverage(null))).contains("minCoverage");
    }

    @Test
    void everyViolationIsReportedNotOnlyTheFirst() {
        WeightSetDraft broken = new WeightSetDraft(null, 70, 30, null);

        assertThat(fields(broken)).containsExactly("factors", "lowMax", "minCoverage");
        assertThat(fields(null)).containsExactly("body");
    }

    private static List<String> fields(WeightSetDraft draft) {
        return WeightSetValidator.validate(draft).stream().map(Violation::field).toList();
    }

    private WeightSetDraft withWeight(int index, String weight) {
        List<FactorDefinition> factors = new ArrayList<>(valid.factors());
        FactorDefinition original = factors.get(index);
        factors.set(index, new FactorDefinition(original.factorId(), new BigDecimal(weight), original.curve()));
        return draft(factors);
    }

    private WeightSetDraft withCurve(List<CurvePoint> curve) {
        List<FactorDefinition> factors = new ArrayList<>(valid.factors());
        FactorDefinition original = factors.getFirst();
        factors.set(0, new FactorDefinition(original.factorId(), original.weight(), curve));
        return draft(factors);
    }

    private WeightSetDraft coverage(String value) {
        return new WeightSetDraft(valid.factors(), 39, 69, value == null ? null : new BigDecimal(value));
    }

    private WeightSetDraft draft(List<FactorDefinition> factors) {
        return new WeightSetDraft(factors, valid.lowMax(), valid.mediumMax(), valid.minCoverage());
    }

    private static CurvePoint point(String input, int risk) {
        return new CurvePoint(new BigDecimal(input), risk);
    }
}
