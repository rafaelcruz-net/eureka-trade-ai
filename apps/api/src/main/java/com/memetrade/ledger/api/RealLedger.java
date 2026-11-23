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

    /**
     * 261121-paper-real-unico U2 (C13, C14, BR1.4): records the slot of {@code txSignature} in the caller's transaction,
     * once and never overwritten. The tenant is the one of the confirmation (it names the audit event of a conflict);
     * the writer labels the conflict metric. A conflict keeps the first slot, is audited (REAL_SLOT_CONFLICT, the
     * signature only abbreviated and as its HMAC) and counted after the commit, and never throws: the confirmation and
     * its entries go on. A slot unknown at the confirmation is never recorded (the caller does not call this).
     *
     * @throws IllegalArgumentException for a blank signature or a negative slot (a defect of the caller)
     */
    RecordSlotResult recordSlot(UUID tenantId, String txSignature, long slot, SlotWriter writer);

    /**
     * 261121-paper-real-unico U2a, Etapa 14b (decision of the founder of 2026-11-22): keeps the marks of zero of the
     * bot wallet ({@code real_position_epoch}) in the caller's transaction, after the entries and the slots the writer
     * recorded in it. Called by the three writers (the confirmation of an order, the fill of a conditional, the
     * confirmation of a deposit or a withdrawal) as their last step. A zero of the quantity of the wallet in a mint
     * (BR2.5) gets a new mark; a late confirmation that lands before the latest mark gets a new one from a read of the
     * whole history (OUT_OF_ORDER), and a row created before the window of a mark lowers it (EARLY_ROW). It only
     * inserts: a mark is never updated nor deleted. Nothing is written for a wallet of another tenant.
     */
    void markEpochs(UUID tenantId, UUID botWalletId);

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

    /**
     * 261121-paper-real-unico U2 (C12, BR1.1 to BR1.6): the rows of these bot wallets of this tenant, of the kinds asked
     * for, handed one by one to {@code consumer} (never as a list), grouped by wallet and, inside it, in the order of
     * {@link NetworkOrderKey}. The slot comes from {@code real_entry_slot} by the signature; a row without it inherits
     * the slot of the last recorded row before it in recording order, computed over EVERY row of the wallet before the
     * kinds are filtered, so the key is the same for every reader. Nothing for a wallet of another tenant; never PAPER;
     * no lock. Joins the read transaction of the caller when there is one, otherwise opens a read-only one; fetched 500
     * rows at a time. Each row delivered SLOT_MISSING is counted in {@code ledger_real_slot_missing_total}.
     */
    void streamInNetworkOrder(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            java.util.Set<RealEntryKind> kinds,
            java.util.function.Consumer<NetworkOrderedEntry> consumer);

    /**
     * {@link #streamInNetworkOrder(UUID, java.util.Collection, java.util.Set, java.util.function.Consumer)} under a hard
     * deadline (NFR1.12): the clock is checked at every row and the time that remains is applied as
     * {@code set local statement_timeout} before the statement and before every block of 500 rows, on the connection of
     * the caller's transaction. The caller restores the previous {@code statement_timeout} (or rolls back to its
     * savepoint).
     *
     * @throws com.memetrade.shared.jdbc.ReadDeadlineExceeded when the deadline passes at a row
     */
    void streamInNetworkOrder(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            java.util.Set<RealEntryKind> kinds,
            com.memetrade.shared.jdbc.ReadDeadline deadline,
            java.util.function.Consumer<NetworkOrderedEntry> consumer);
}
