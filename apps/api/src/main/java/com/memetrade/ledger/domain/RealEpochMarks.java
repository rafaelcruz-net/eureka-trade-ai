package com.memetrade.ledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 261121-paper-real-unico U2a, Etapa 14b (decision of the founder of 2026-11-22): the marks of zero a writer adds after
 * its entries, pure. The zero points come from {@link RealEpochs} (the one source of the epochs), fed either with the
 * rows after the current marks (a restricted read) or, when the count of fills of some mark disagreed with the rows
 * ({@code invalid} not empty), with the whole history. The plan only ADDS marks; the latest mark of a mint supersedes
 * the older ones, which stay as they were.
 *
 * <ul>
 *   <li>a zero found in the rows read gets a mark: its key, the fills up to it (in a restricted read, plus the fills of
 *       the current mark, which were not read again) and a window from the earliest row of the open epoch, or
 *       {@code now} when the mint is closed. Reason ZERO, or OUT_OF_ORDER for a mint whose mark was invalid;
 *   <li>in a full read, a mark that already is the zero found is not written again; an invalid mark of a mint that has
 *       no zero any more becomes a full-read mark (no zero, 0 fills, the window from the first fill of the mint);
 *   <li>a row this transaction created before the window of a mark the plan does not replace lowers that window to it
 *       (EARLY_ROW): the zero stays, and every row after it is inside the window again.
 * </ul>
 */
public final class RealEpochMarks {

    private RealEpochMarks() {}

    /**
     * @param current the latest mark of each mint of the wallet
     * @param read the epochs of the rows read: after the current marks, or the whole history when {@code invalid} is
     *     not empty
     * @param invalid the mints whose mark disagreed with the count of their fills (a full read was made)
     * @param earliestWrite the earliest creation instant of the rows this transaction wrote, when it wrote some
     * @param now the instant of the writer
     */
    public static List<EpochMark> plan(
            Map<String, EpochMark> current,
            RealEpochs read,
            Set<String> invalid,
            Optional<Instant> earliestWrite,
            Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(invalid, "invalid");
        Objects.requireNonNull(earliestWrite, "earliestWrite");
        Objects.requireNonNull(now, "now");
        boolean fullRead = !invalid.isEmpty();
        Map<String, EpochMark> planned = new TreeMap<>();
        for (String mint : read.mints()) {
            EpochMark mark = current.get(mint);
            EpochMarkReason reason = invalid.contains(mint) ? EpochMarkReason.OUT_OF_ORDER : EpochMarkReason.ZERO;
            Optional<RealEpochs.ZeroPoint> zero = read.zeroPoint(mint);
            if (zero.isPresent()) {
                long base = fullRead || mark == null ? 0 : mark.fillsThrough();
                long through = Math.addExact(base, zero.get().fillsThrough());
                Optional<com.memetrade.ledger.api.NetworkOrderKey> key =
                        Optional.of(zero.get().key());
                if (fullRead && mark != null && mark.sameZeroAs(key, through)) {
                    continue;
                }
                planned.put(mint, new EpochMark(mint, key, through, earliest(read.openedAt(mint), now), reason));
            } else if (fullRead && mark != null && mark.zero().isPresent()) {
                Instant first = read.openedAt(mint).orElse(now);
                planned.put(mint, new EpochMark(mint, Optional.empty(), 0, first, EpochMarkReason.OUT_OF_ORDER));
            }
        }
        List<EpochMark> plan = new ArrayList<>(planned.values());
        earliestWrite.ifPresent(early -> plan.addAll(lowered(current, planned.keySet(), early)));
        return plan;
    }

    private static Instant earliest(Optional<Instant> opened, Instant now) {
        return opened.filter(at -> at.isBefore(now)).orElse(now);
    }

    /** EARLY_ROW: the marks the plan keeps whose window starts after a row this transaction created. */
    private static List<EpochMark> lowered(Map<String, EpochMark> current, Set<String> replaced, Instant early) {
        Set<String> skip = new HashSet<>(replaced);
        List<EpochMark> lowered = new ArrayList<>();
        new TreeMap<>(current).forEach((mint, mark) -> {
            if (!skip.contains(mint) && early.isBefore(mark.windowFrom())) {
                lowered.add(new EpochMark(mint, mark.zero(), mark.fillsThrough(), early, EpochMarkReason.EARLY_ROW));
            }
        });
        return lowered;
    }
}
