package com.memetrade.pnl.real;

import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.real.ReconciliationResult.Check;
import com.memetrade.pnl.real.ReconciliationResult.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 261121-paper-real-unico U2 (F3, BR7.5, BR8.1 to BR8.5, BR3.4): the reconciliation, a pure comparison by exact
 * equality, with no tolerance:
 *
 * <ul>
 *   <li>REALIZED and FEES: the sum of the lines equals the mode from the ledger, in the period and since the beginning
 *       (BR8.1, BR8.2: a period also checks ALL, from the same pass);
 *   <li>UNREALIZED: only since the beginning, both known and equal, or both unknown (BR8.1, AC5.4.3);
 *   <li>COST_CONSERVATION, QUANTITY_CONSERVATION, EVENT_SUMS: the differences the calculation measured (BR8.3, BR8.4);
 *   <li>UNOWNED_WITHOUT_REASON: a line "Sem bot" different from zero without a reason (BR8.5);
 *   <li>UNKNOWN_BOT: an order of a bot outside the directory of the person (BR3.4), a mismatch on its own.
 * </ul>
 */
public final class RealPnlReconciliation {

    private RealPnlReconciliation() {}

    /** One side of the reconciliation: realized, fees and unrealized. */
    public record Side(long realizedLamports, long feesLamports, LamportsValue unrealized) {
        public Side {
            Objects.requireNonNull(unrealized, "unrealized");
        }
    }

    /**
     * @param costConservationDiff the absolute differences of BR8.3 (cost) summed over wallets and mints
     * @param quantityConservationDiff the absolute differences of BR8.3 and BR2.5 (quantity), in base units
     * @param eventSumsDiff the absolute differences of BR8.4 summed over the events
     * @param unownedWithoutReason the line "Sem bot" has a value and no reason
     */
    public record Inputs(
            PnlWindow window,
            Side linesInWindow,
            Side ledgerInWindow,
            Side linesAll,
            Side ledgerAll,
            long costConservationDiff,
            long quantityConservationDiff,
            long eventSumsDiff,
            boolean unownedWithoutReason) {

        public Inputs {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(linesInWindow, "linesInWindow");
            Objects.requireNonNull(ledgerInWindow, "ledgerInWindow");
            Objects.requireNonNull(linesAll, "linesAll");
            Objects.requireNonNull(ledgerAll, "ledgerAll");
        }
    }

    public static ReconciliationResult reconcile(Inputs in) {
        List<Check> failed = new ArrayList<>();
        Map<String, Long> diffs = new TreeMap<>();
        long realized = difference(
                in.linesInWindow().realizedLamports(),
                in.ledgerInWindow().realizedLamports(),
                in.linesAll().realizedLamports(),
                in.ledgerAll().realizedLamports());
        if (realized != 0) {
            failed.add(Check.REALIZED);
            diffs.put(Check.REALIZED.name(), realized);
        }
        long fees = difference(
                in.linesInWindow().feesLamports(),
                in.ledgerInWindow().feesLamports(),
                in.linesAll().feesLamports(),
                in.ledgerAll().feesLamports());
        if (fees != 0) {
            failed.add(Check.FEES);
            diffs.put(Check.FEES.name(), fees);
        }
        Optional<Long> unrealized = Optional.empty();
        LamportsValue lines = in.linesAll().unrealized();
        LamportsValue ledger = in.ledgerAll().unrealized();
        if (lines instanceof LamportsValue.Known known && ledger instanceof LamportsValue.Known expected) {
            long difference = Math.subtractExact(known.lamports(), expected.lamports());
            unrealized = Optional.of(difference);
            if (difference != 0) {
                failed.add(Check.UNREALIZED);
                diffs.put(Check.UNREALIZED.name(), difference);
            }
        } else if ((lines instanceof LamportsValue.Known) != (ledger instanceof LamportsValue.Known)) {
            failed.add(Check.UNREALIZED);
        }
        measured(failed, diffs, Check.COST_CONSERVATION, in.costConservationDiff());
        measured(failed, diffs, Check.QUANTITY_CONSERVATION, in.quantityConservationDiff());
        measured(failed, diffs, Check.EVENT_SUMS, in.eventSumsDiff());
        if (in.unownedWithoutReason()) {
            failed.add(Check.UNOWNED_WITHOUT_REASON);
        }
        return new ReconciliationResult(
                in.window(),
                failed.isEmpty() ? Status.MATCH : Status.MISMATCH,
                realized,
                fees,
                unrealized,
                failed,
                diffs);
    }

    /** BR3.4: the calculation stopped at an order of a bot that is not a REAL bot of the person. */
    public static ReconciliationResult unknownBot(PnlWindow window) {
        return new ReconciliationResult(
                window, Status.MISMATCH, 0, 0, Optional.empty(), List.of(Check.UNKNOWN_BOT), Map.of());
    }

    /** The difference in the period, or, when it is equal there, the one since the beginning. */
    private static long difference(long lines, long ledger, long linesAll, long ledgerAll) {
        long inWindow = Math.subtractExact(lines, ledger);
        return inWindow != 0 ? inWindow : Math.subtractExact(linesAll, ledgerAll);
    }

    private static void measured(List<Check> failed, Map<String, Long> diffs, Check check, long difference) {
        if (difference != 0) {
            failed.add(check);
            diffs.put(check.name(), difference);
        }
    }
}
