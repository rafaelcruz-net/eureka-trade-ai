package com.memetrade.riskassessment.api;

import java.time.Instant;
import java.util.Optional;

/** A stored weight version as read by the administration (U8). */
public record WeightSetView(
        int version,
        WeightSetStatus status,
        WeightSetDraft definition,
        Instant createdAt,
        String createdBy,
        Optional<Instant> activatedAt,
        Optional<Instant> retiredAt) {}
