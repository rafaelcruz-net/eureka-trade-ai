package com.memetrade.marketdata.api;

import java.time.Instant;

/**
 * Published after the transaction of a collection committed (F1 step 9), whatever its result: the RiskAssessment
 * module listens and recalculates the assessments (BR3.7). MarketData never depends on the listener.
 */
public record MarketCollectionCompleted(String sourceId, SourceStatus.State status, Instant completedAt) {}
