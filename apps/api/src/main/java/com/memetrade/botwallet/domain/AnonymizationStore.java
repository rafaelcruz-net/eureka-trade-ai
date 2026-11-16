package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5 (BR6.1, BR6.2, U5-NFR1.3): persistence port of the closing of a bot wallet by an
 * anonymization, in the transaction of the anonymization.
 */
public interface AnonymizationStore {

    /** The ACTIVE bot wallet of the person in this tenant, locked FOR UPDATE until the end of the transaction. */
    Optional<WalletToClose> lockActiveWallet(UUID tenantId, UUID userId);

    /** BR6.1: withdrawals of the wallet not final (REQUESTED, SIGNED, SENT, AMBIGUOUS). */
    int withdrawalsInProgress(UUID botWalletId);

    /** BR6.2: CLOSED now, without its main wallet (the address of the bot wallet stays until the DEACTIVATE). */
    void closeWallet(UUID botWalletId, Instant at);

    /**
     * BR6.2: the main wallet of every bot wallet of the person, the messages of its consent challenges and the signed
     * transactions and digests of its withdrawals (marked redacted); returns how many rows changed.
     */
    int clearPersonalData(UUID tenantId, UUID userId, Instant at);

    /** U5-NFR1.3: the SECURITY DEFINER function of the migration 0021; returns the rows redacted. */
    int redactAudit(String botWalletAddress);

    /** The wallet being closed. */
    record WalletToClose(UUID botWalletId, String address) {}
}
