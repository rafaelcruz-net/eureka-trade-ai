package com.memetrade.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Interface M2 of the REAL ledger (contract-summary.md): append-only, separate from PAPER (BR6.3). Only the BotWallet
 * writes in the U1; reads always filter by tenant.
 */
public interface RealLedger {

    /**
     * Records the entry in the caller's transaction; {@code false} when an entry with the same idempotency key already
     * exists (it is never recorded twice).
     *
     * @throws IllegalArgumentException when the entry breaks the sign convention of BR3.5
     */
    boolean record(RealEntry entry);

    /** Balance of the bot wallet of this tenant; an empty balance when the wallet belongs to another tenant. */
    RealBalance balance(UUID tenantId, UUID botWalletId);

    /**
     * 261113-trading-real-bot U3 (Q11, BR5.2): the open positions of the bot wallet of this tenant, from its swap fills;
     * empty for a wallet of another tenant.
     */
    List<RealPosition> positions(UUID tenantId, UUID botWalletId);

    /**
     * 261113-trading-real-bot U3 (Q11, BR4.3): the deposits and withdrawals (with their fee) recorded strictly after
     * {@code since}; none for a wallet of another tenant.
     */
    RealFlows flowsSince(UUID tenantId, UUID botWalletId, Instant since);

    /**
     * 261113-trading-real-bot U3 (review R-01): {@link #balance}, {@link #positions} and, when {@code flowsSince} is
     * present, {@link #flowsSince} in ONE statement (one snapshot), for the barrier and its jobs; nothing for a wallet of
     * another tenant.
     */
    RealLedgerView view(UUID tenantId, UUID botWalletId, java.util.Optional<Instant> flowsSince);

    /**
     * 261113-trading-real-bot U5 (BR2.1, BR2.3): the base units of every mint of the bot wallet of this tenant, from its
     * swap fills (IN adds, OUT removes) and its COMPENSATION entries of a token (with their sign); a mint at zero is
     * omitted; nothing for a wallet of another tenant. Compared by the reconciliation with the token accounts of the
     * network.
     */
    java.util.Map<String, Long> tokenBalances(UUID tenantId, UUID botWalletId);

    /**
     * 261113-trading-real-bot U7 (BR7, U7-NFR3.3, U7-NFR3.8, logical-components.md "streamEntries"): the entries of
     * these bot wallets of this tenant created in {@code [from, to)}, handed one by one to {@code consumer} in a
     * read-only transaction with a fetch size of 500, never as a list in memory. Ordered so the entries of one
     * reference are adjacent: by the first instant of the reference in the period, then the reference, then the entry.
     * The query uses the index {@code (tenant_id, created_at)}; the wallets of the person are filtered after it.
     * Nothing for an empty set of wallets or a wallet of another tenant; never PAPER.
     */
    void streamEntries(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            Instant from,
            Instant to,
            java.util.function.Consumer<RealEntryRecord> consumer);
}
