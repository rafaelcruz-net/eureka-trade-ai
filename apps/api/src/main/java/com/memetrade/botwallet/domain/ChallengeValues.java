package com.memetrade.botwallet.domain;

/** Mode and cap written in a consent challenge (BR1.9). */
public record ChallengeValues(OperationMode mode, long capLamports) {}
