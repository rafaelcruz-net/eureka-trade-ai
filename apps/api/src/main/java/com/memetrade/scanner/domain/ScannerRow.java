package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.riskassessment.api.AssessmentResult;

/**
 * One scanner line (C2 ScannerRow): the four market fields resolved between the sources (with divergence), the age,
 * the coverage of the 5 fields (price, liquidity, 24 h volume, holders and age) and the risk summary.
 */
public record ScannerRow(
        String chain,
        String mint,
        String symbol,
        String name,
        int coverageInformed,
        int coverageTotal,
        ResolvedField price,
        ResolvedField liquidity,
        ResolvedField volume24h,
        ResolvedField holders,
        MarketValue ageMinutes,
        AssessmentResult risk) {}
