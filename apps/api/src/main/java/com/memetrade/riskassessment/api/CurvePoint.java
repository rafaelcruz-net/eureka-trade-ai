package com.memetrade.riskassessment.api;

import java.math.BigDecimal;

/**
 * One point of a factor curve: an input value (exact decimal, not negative) and its risk from 0 to 100; between points
 * the risk is interpolated linearly (BR3.1).
 */
public record CurvePoint(BigDecimal input, int risk) {}
