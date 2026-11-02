package com.memetrade.riskassessment.domain;

import com.memetrade.riskassessment.api.CurvePoint;
import com.memetrade.riskassessment.api.FactorDefinition;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.api.WeightSetDraft;
import java.math.BigDecimal;
import java.util.List;

/** Version 1 of the weights written in the test straight from BR4.3 (never read back from the migration). */
public final class WeightsV1 {

    private WeightsV1() {}

    public static WeightSetDraft draft() {
        return new WeightSetDraft(
                List.of(
                        factor(FactorId.LIQUIDITY, "35.0000", 1000, 100, 10000, 60, 50000, 25, 100000, 0),
                        factor(FactorId.HOLDERS, "25.0000", 50, 100, 200, 60, 1000, 25, 5000, 0),
                        factor(FactorId.VOLUME_24H, "20.0000", 1000, 100, 10000, 60, 100000, 25, 1000000, 0),
                        factor(FactorId.PRICE_CHANGE_1H, "20.0000", 0, 0, 10, 30, 30, 70, 60, 100)),
                39,
                69,
                new BigDecimal("80.0000"));
    }

    public static WeightSet weights() {
        return WeightSet.of(1, draft());
    }

    static FactorDefinition factor(FactorId id, String weight, long... pairs) {
        List<CurvePoint> curve = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            curve.add(new CurvePoint(BigDecimal.valueOf(pairs[i]), (int) pairs[i + 1]));
        }
        return new FactorDefinition(id, new BigDecimal(weight), curve);
    }
}
