package com.memetrade.botwallet.domain;

import java.util.List;
import java.util.UUID;

/** Persistence port of the deposit scan of the worker (BR3.1, BR3.2). */
public interface DepositStore {

    /** An ACTIVE bot wallet with its tenant, registered main wallet and deposit cursor. */
    record WalletToScan(
            UUID tenantId, UUID botWalletId, String botWalletAddress, String mainWalletAddress, String cursor) {}

    /** A finalized deposit to record, with its classification. */
    record RecordedDeposit(ChainPort.IncomingTransfer transfer, boolean knownSource, boolean notifyPerson) {}

    List<WalletToScan> activeWallets();

    /**
     * Records the deposit, its DEPOSIT ledger entry ({@code dep:<signature>}), its audit event and, for an unknown
     * source, a reconciliation mark, in one transaction; {@code false} when the signature was already recorded.
     */
    boolean record(WalletToScan wallet, RecordedDeposit deposit);

    void advanceCursor(WalletToScan wallet, String lastSignature);
}
