package com.memetrade.ledger.api;

import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderKind;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.OrderStatus;
import com.memetrade.ledger.api.PaperEnums.PositionStatus;
import com.memetrade.ledger.api.PaperExecution.FillDetails;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY way the {@code pnl} module (and the routes of {@code ledger.web}) read the ledger (logical-components.md).
 * Read only, except {@link #lockAccountForMarking}, which locks the account so the marking sees balance and positions
 * of the same instant (BR2.8, BR7.3). Every query of customer data filters by tenant, person and {@code PAPER}
 * (BR9.1, BR9.2); the jobs pass the tenant and person read from the account row itself.
 */
public interface LedgerReadModel {

    /** The PAPER account of a bot, whoever owns it (the caller compares tenant and person). */
    Optional<AccountView> account(UUID botId);

    /** Available balance: the sum of every USDC entry of the bot (BR2.5), by a covering index. */
    BigDecimal balance(UUID botId);

    /** Every position row of the bot, ordered by (chain, mint). */
    List<PositionView> positions(UUID botId);

    /** The positions with quantity above zero of a person (optionally one bot), ordered by bot, chain and mint. */
    List<PositionView> openPositions(UUID tenantId, UUID ownerUserId, Optional<UUID> botId);

    /** The fills of a person with {@code filledAt} in [from, to), fees already net of their compensations (BR6.1). */
    List<FillView> fills(UUID tenantId, UUID ownerUserId, Optional<UUID> botId, Instant from, Instant to);

    /** The daily summary rows of whole UTC days in [fromDay, toDay) (performance-design.md). */
    List<DailySummaryRow> dailySummary(
            UUID tenantId, UUID ownerUserId, Optional<UUID> botId, LocalDate fromDay, LocalDate toDay);

    /** Entries of a person by {@code createdAt} descending and {@code entryId}, by cursor (F8, BR10.2). */
    EntryPage entries(UUID tenantId, UUID ownerUserId, Optional<UUID> botId, Optional<EntryKey> after, int limit);

    /** Orders of a bot by {@code createdAt} descending and {@code orderId}, each once with its fill (F8). */
    TradePage trades(UUID tenantId, UUID ownerUserId, UUID botId, Optional<TradeKey> after, int limit);

    /** Instant of the last filled order of the bot, if any (C13). */
    Optional<Instant> lastFilledAt(UUID botId);

    /** Accounts OPEN or CLOSING, which the marking visits (F5). */
    List<AccountView> accountsToMark();

    /** Every person with at least one PAPER account, which the reconciliation visits (F9). */
    List<PersonRef> peopleWithAccounts();

    /**
     * Locks the account row ({@code FOR UPDATE}, with {@code lock_timeout} = {@code lockTimeout}) and returns its
     * balance and positions read under the lock. Requires an open transaction (the marking's own).
     *
     * @throws org.springframework.dao.PessimisticLockingFailureException when the lock is not obtained in time
     */
    MarkingSnapshot lockAccountForMarking(UUID botId, Duration lockTimeout);

    /** Every fact the reconciliation of one person compares (BR8.2), read in ONE snapshot by the caller. */
    ReconciliationFacts reconciliationFacts(UUID tenantId, UUID ownerUserId);

    /** The policy in force (BR1.1), for the exit valuation of the P&L and the equity (BR6.2). */
    PolicyView policy();

    record PolicyView(String version, BigDecimal spreadPct, BigDecimal feePct) {}

    record AccountView(UUID botId, UUID tenantId, UUID ownerUserId, AccountStatus status, Instant createdAt) {}

    record PositionView(
            UUID positionId,
            UUID botId,
            String chain,
            String tokenMint,
            int tokenDecimals,
            BigDecimal quantity,
            BigDecimal costBasis,
            PositionStatus status,
            Instant updatedAt) {}

    /** A fill seen by the P&L: {@code realized} is zero for a buy (a buy realizes nothing), set for SELL and CLOSE. */
    record FillView(
            UUID orderId,
            UUID botId,
            OrderKind kind,
            OrderSide side,
            String chain,
            String tokenMint,
            BigDecimal filledQuantity,
            BigDecimal realized,
            BigDecimal fees,
            Instant filledAt) {}

    record DailySummaryRow(
            UUID botId,
            String chain,
            String tokenMint,
            LocalDate utcDay,
            BigDecimal realized,
            BigDecimal fees,
            int fillCount) {}

    record EntryKey(Instant createdAt, UUID entryId) {}

    record EntryView(
            UUID entryId,
            UUID botId,
            EntryType type,
            String asset,
            BigDecimal amount,
            String referenceId,
            Optional<UUID> compensatesEntryId,
            Optional<UUID> orderId,
            Optional<String> simulationPolicyVersion,
            Instant createdAt) {}

    record EntryPage(List<EntryView> items, Optional<EntryKey> next, long total) {}

    record TradeKey(Instant createdAt, UUID orderId) {}

    record TradeView(
            UUID orderId,
            UUID botId,
            OrderKind kind,
            OrderSide side,
            String chain,
            String tokenMint,
            OrderStatus status,
            Optional<NotFilledReason> notFilledReason,
            BigDecimal requestedQuantity,
            String idempotencyKey,
            long limitsVersion,
            Instant createdAt,
            Optional<FillDetails> fill) {}

    record TradePage(List<TradeView> items, Optional<TradeKey> next, long total) {}

    record PersonRef(UUID tenantId, UUID ownerUserId) {}

    record MarkingSnapshot(AccountView account, BigDecimal balance, List<PositionView> positions) {}

    /**
     * @param orders every FILLED or PARTIALLY_FILLED order with its fill and the entries that reference it
     * @param orphans entries whose reference is neither an order, nor a FUNDING key, nor an existing entry
     * @param positions each position against the replay of the fills of its bot and token, in {@code filledAt} order
     * @param summaries each daily summary row against the direct sum of the fills of that day
     */
    record ReconciliationFacts(
            List<OrderFacts> orders,
            List<OrphanEntry> orphans,
            List<PositionFacts> positions,
            List<SummaryFacts> summaries) {}

    /**
     * @param expected the sum of the fill amounts by BR2.2 or BR5.1 plus the compensations of entries of this order
     * @param found the sum of the entries that reference the order plus those same compensations
     */
    record OrderFacts(UUID orderId, UUID botId, int entryCount, BigDecimal expected, BigDecimal found) {}

    record OrphanEntry(UUID entryId, UUID botId, BigDecimal amount) {}

    record PositionFacts(
            UUID positionId,
            UUID botId,
            BigDecimal quantity,
            BigDecimal costBasis,
            BigDecimal replayedQuantity,
            BigDecimal replayedCostBasis) {}

    record SummaryFacts(
            UUID botId,
            String chain,
            String tokenMint,
            LocalDate utcDay,
            BigDecimal summaryRealized,
            BigDecimal summaryFees,
            BigDecimal directRealized,
            BigDecimal directFees) {}
}
