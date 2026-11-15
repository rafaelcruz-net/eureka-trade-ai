package com.memetrade.riskcontrol.real.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 261113-trading-real-bot U3 (entities.md DaySnapshot, BR4.4): the photo of a bot wallet at the start of a UTC day,
 * table {@code real_risk_day}. One per wallet and day, never changed after it is recorded.
 *
 * @param startValueLamports the value of the wallet when the photo was taken (balance plus positions, BR5.2)
 * @param startCapLamports the cap of the wallet when the photo was taken
 */
public record DaySnapshot(
        UUID tenantId,
        UUID botWalletId,
        LocalDate day,
        long startValueLamports,
        long startCapLamports,
        Instant takenAt,
        TakenBy takenBy) {

    /** Who recorded the photo: the job after 00:00 UTC or the first buy of the day without a photo. */
    public enum TakenBy {
        DAY_JOB,
        FIRST_EVALUATION
    }

    public DaySnapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(takenAt, "takenAt");
        Objects.requireNonNull(takenBy, "takenBy");
    }
}
