package com.memetrade.botwallet.api;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * M1 {@code readRealState} (261113-trading-real-bot U3, entities.md RealStateView, Q11): the REAL state of the ACTIVE bot
 * wallet of one person. Lamports are {@code long}. The contract field {@code pausedReason} became the set
 * {@code pausedReasons}, because the BotWallet keeps a set of reasons; {@code pausedSince} has the start of each active
 * pause (BR4.5 reads the day of the DAILY_LOSS pause from it).
 *
 * @param realEnabled the REAL switch of the environment ({@code memetrade.real.enabled})
 * @param released a release of the person is in force on the network of the environment, after its quarantine
 */
public record RealStateView(
        UUID botWalletId,
        boolean realEnabled,
        boolean released,
        RealOperationMode mode,
        long capLamports,
        long feeReserveLamports,
        Set<RealPauseReason> pausedReasons,
        Map<RealPauseReason, Instant> pausedSince) {

    public RealStateView {
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(mode, "mode");
        pausedReasons = pausedReasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(pausedReasons));
        pausedSince = pausedSince.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(pausedSince));
    }

    public boolean paused() {
        return !pausedReasons.isEmpty();
    }
}
