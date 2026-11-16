package com.memetrade.botwallet.domain;

import java.util.Optional;

/** 261113-trading-real-bot U5 (BR1.4): ACTIVE with every gate green, or BLOCKED with the first red gate. */
public record GateOutcome(ReleaseState state, Optional<BlockedReason> blockedReason, GateSnapshot snapshot) {}
