package com.memetrade.botwallet.api;

import java.time.Instant;
import java.util.UUID;

/**
 * M7 (contract-summary.md, owner U5; consumers: the M1 {@code readRealState}, the gates of the release, the U7 and the
 * administrative routes R4). The kill switch REAL lives in {@code botwallet}, the owner of the REAL pauses
 * (logical-components.md, iteration 1). {@link #current()} reads the one row of {@code real_kill_switch} live, without
 * cache, so every consumer refuses a new order the moment the switch commits (U5-NFR3.5).
 */
public interface RealKillSwitchState {

    /** The kill switch now. */
    KillSwitchView current();

    /**
     * BR3.1: switches the kill switch REAL on in a short transaction (compare-and-set on the version), audited with the
     * administrator. Switching it on when it is already on changes nothing ({@code changed = false}). The unsent orders
     * are cancelled after the commit by the cancellation job (every minute while it stays on).
     *
     * @param reason 1 to 500 characters ({@link RealAdminRefused} {@code VALIDATION_FAILED} otherwise)
     */
    KillSwitchChange activate(UUID adminId, String reason);

    /** BR3.2: switches it off; no bot is restarted and the other pauses stay. Off when already off changes nothing. */
    KillSwitchChange deactivate(UUID adminId, String reason);

    /**
     * BR3.1: while the switch is on, cancels the unsent orders of everybody (M6 {@code cancelUnsent(ALL, KILL_SWITCH)})
     * in its own transaction; zero with the switch off. Called after the commit of the switch and by the job of every
     * minute until nothing is left.
     */
    int cancelUnsentWhileActive();

    /** The kill switch after a request, and whether the request changed it. */
    record KillSwitchChange(KillSwitchView view, boolean changed) {}

    /**
     * The kill switch REAL as the consumers read it.
     *
     * @param activatedAt the start of the KILL_SWITCH pause, when active
     * @param version the compare-and-set version of the row
     */
    record KillSwitchView(boolean active, Instant activatedAt, UUID activatedBy, String reason, long version) {

        public static KillSwitchView off(long version) {
            return new KillSwitchView(false, null, null, null, version);
        }

        @Override
        public String toString() {
            return "KillSwitchView[active=" + active + ", version=" + version + "]";
        }
    }
}
