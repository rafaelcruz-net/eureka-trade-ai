package com.memetrade.riskassessment.domain;

import com.memetrade.riskassessment.api.CurvePoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Risk of an input on a factor curve (BR3.1): at or below the first point, the risk of the first point; at or above the
 * last, the risk of the last; between two points, linear interpolation rounded to the nearest integer, half up. The
 * rounding is applied once to the exact value {@code (r1 x (x2 - x1) + (x - x1) x (r2 - r1)) / (x2 - x1)}, which is
 * never negative, so a decreasing segment rounds half up like an increasing one.
 */
public final class Curve {

    private Curve() {}

    public static int risk(List<CurvePoint> curve, BigDecimal input) {
        CurvePoint first = curve.getFirst();
        CurvePoint last = curve.getLast();
        if (input.compareTo(first.input()) <= 0) {
            return first.risk();
        }
        if (input.compareTo(last.input()) >= 0) {
            return last.risk();
        }
        for (int i = 1; i < curve.size(); i++) {
            CurvePoint upper = curve.get(i);
            if (input.compareTo(upper.input()) <= 0) {
                return interpolate(curve.get(i - 1), upper, input);
            }
        }
        return last.risk();
    }

    private static int interpolate(CurvePoint lower, CurvePoint upper, BigDecimal input) {
        BigDecimal span = upper.input().subtract(lower.input());
        BigDecimal numerator = BigDecimal.valueOf(lower.risk())
                .multiply(span)
                .add(input.subtract(lower.input()).multiply(BigDecimal.valueOf((long) upper.risk() - lower.risk())));
        return numerator.divide(span, 0, RoundingMode.HALF_UP).intValueExact();
    }
}
