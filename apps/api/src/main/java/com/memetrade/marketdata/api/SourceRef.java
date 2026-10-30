package com.memetrade.marketdata.api;

/** Identity of a market data source; {@code simulated} is true for the local simulated source (FR4.1). */
public record SourceRef(String sourceId, String name, boolean simulated) {}
