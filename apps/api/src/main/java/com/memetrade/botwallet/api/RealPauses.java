package com.memetrade.botwallet.api;

import java.util.UUID;

/**
 * M1 commands {@code pauseUser} and {@code resumeUser} (261113-trading-real-bot U3, BR4.3, BR4.5): add or remove ONE
 * reason of pause of the ACTIVE wallet of a person, in the transaction of the caller (the cycle of the bot, or the job).
 * Both are idempotent and change nothing of the other reasons; an effective change is audited.
 */
public interface RealPauses {

    PauseOutcome pauseUser(UUID tenantId, UUID userId, RealPauseReason reason);

    /** Removes only the pause of the same reason (contract M1). */
    PauseOutcome resumeUser(UUID tenantId, UUID userId, RealPauseReason reason);

    enum PauseOutcome {
        PAUSED,
        ALREADY_PAUSED,
        RESUMED,
        NOT_PAUSED,
        NO_WALLET
    }
}
