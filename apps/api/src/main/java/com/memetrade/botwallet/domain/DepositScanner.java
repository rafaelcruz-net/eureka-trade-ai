package com.memetrade.botwallet.domain;

/**
 * Deposit scan of the worker (BR3.1, BR3.2): for each ACTIVE wallet, the finalized history after its cursor, oldest
 * first; each new signature becomes one deposit; a source other than the registered main wallet is marked unknown
 * (it stays in the balance and never goes back to the source); deposits below 0.001 SOL enter the balance without a
 * notice. A network outage skips the wallet: the next cycle starts again from the stored cursor.
 */
public final class DepositScanner {

    public static final long NOTICE_MINIMUM_LAMPORTS = 1_000_000L;
    static final int PAGE = 100;

    private final ChainPort chain;
    private final DepositStore store;

    public DepositScanner(ChainPort chain, DepositStore store) {
        this.chain = chain;
        this.store = store;
    }

    /** One cycle; returns how many deposits were recorded. */
    public int scan() {
        int recorded = 0;
        for (DepositStore.WalletToScan wallet : store.activeWallets()) {
            try {
                ChainPort.IncomingPage page = chain.incomingSince(wallet.botWalletAddress(), wallet.cursor(), PAGE);
                for (ChainPort.IncomingTransfer transfer : page.transfers()) {
                    boolean known = wallet.mainWalletAddress().equals(transfer.sourceAddress());
                    boolean notify = transfer.lamports() >= NOTICE_MINIMUM_LAMPORTS;
                    if (store.record(wallet, new DepositStore.RecordedDeposit(transfer, known, notify))) {
                        recorded++;
                    }
                }
                if (page.lastSignature() != null) {
                    store.advanceCursor(wallet, page.lastSignature());
                }
            } catch (ChainUnavailableException outage) {
                // Nothing is lost: the cursor did not move, the next cycle reads the same entries again.
            }
        }
        return recorded;
    }
}
