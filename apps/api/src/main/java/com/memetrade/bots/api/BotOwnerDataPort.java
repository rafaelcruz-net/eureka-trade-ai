package com.memetrade.bots.api;

import java.util.UUID;

/**
 * J2 (contract-summary.md): what the Privacy module may know of, and do to, the bots of a person, without reading a
 * table of Bots. u4-data-requests adds the count for the Access summary (BR4.8); u5-anonymization adds the anonymization
 * of the bots of the person (BR5.2, BR5.3).
 */
public interface BotOwnerDataPort {

    /** The name every bot of an anonymized person gets (BR5.2). */
    String ANONYMIZED_BOT_NAME = "Bot anonimizado";

    /** Every bot of the person in that tenant, in any state (DRAFT to STOPPED); 0 when there is none. */
    long countOwnerBots(UUID tenantId, UUID ownerUserId);

    /**
     * u5-anonymization (BR5.2, BR5.3, BR5.6): runs only inside an existing transaction (Propagation.MANDATORY). Locks
     * through the global lock order of the module (the system pause shared, the owner row, then every bot of the person
     * in one statement ordered by {@code bot_id}), takes ACTIVE, PAUSED and CLOSING_PENDING bots straight to STOPPED
     * without any closing, leaves DRAFT and VALIDATED as they are, and renames every bot of the person to
     * {@link #ANONYMIZED_BOT_NAME}. Open PAPER positions stay as they are; no order is created. Repeating it changes
     * nothing more.
     *
     * @return the number of bots taken to STOPPED now
     */
    int anonymizeOwnerBots(UUID tenantId, UUID ownerUserId);
}
