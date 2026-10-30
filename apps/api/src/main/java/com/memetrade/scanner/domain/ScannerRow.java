package com.memetrade.scanner.domain;

import com.memetrade.marketdata.api.MarketValue;

/** One scanner line: the 5 market fields as KNOWN/UNKNOWN plus coverage (BR3.5). No risk in U1. */
public record ScannerRow(
        String chain,
        String mint,
        String symbol,
        String name,
        int coverageInformed,
        int coverageTotal,
        MarketValue price,
        MarketValue liquidity,
        MarketValue volume24h,
        MarketValue holders,
        MarketValue ageMinutes) {}
