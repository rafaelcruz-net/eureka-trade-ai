package com.memetrade.botwallet.domain;

import java.time.Duration;

/**
 * Settings of the withdrawal follow-up: compute unit limit and price of the transfer (the fee is known before signing),
 * withdrawals per cycle (20, reliability-design.md) and the lease that hides a claimed withdrawal from other workers.
 */
public record WithdrawalSettings(int computeUnitLimit, long computeUnitPrice, int batchSize, Duration lease) {}
