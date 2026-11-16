package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5: persistence port of the restore check (BR2.5): the one open marker, the bot wallets to
 * check, the signatures the bot wallet module knows (withdrawals and deposits) and the confirmation of the restore.
 */
public interface RestoreStore {

    /** Opens the marker CHECK_PENDING when none is open; {@code false} when one already was. */
    boolean openCheckPending(Instant at);

    Optional<Marker> openMarker();

    /** Every bot wallet whose address is still known, with its deposit cursor (the newest signature the scan read). */
    List<WalletToCheck> walletsToCheck();

    /** The signatures of {@code candidates} the bot wallet module recorded (its withdrawals and its deposits). */
    Set<String> knownSignatures(UUID botWalletId, Collection<String> candidates);

    /** CHECK_PENDING to RESTORE with the number of unknown signatures. */
    boolean toRestore(UUID markerId, int unknownSignatures, Instant at);

    /** Clears the open marker (the check found nothing, or the administrator confirmed the restore). */
    boolean clear(UUID markerId, UUID confirmedBy, Instant at);

    /** BR2.5: every ACTIVE wallet has a run AFTER_RESTORE newer than {@code detectedAt} and its newest one is CLEAN. */
    boolean everyWalletCleanAfter(Instant detectedAt);

    /** The open marker. */
    record Marker(UUID markerId, String phase, Instant openedAt, Instant detectedAt) {

        public boolean pending() {
            return "CHECK_PENDING".equals(phase);
        }
    }

    /** A bot wallet to check. */
    record WalletToCheck(UUID botWalletId, String address, String depositCursor) {}
}
