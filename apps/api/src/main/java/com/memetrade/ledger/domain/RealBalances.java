package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealEntryKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Balance calculation of BR3.5 over the REAL ledger rows of one wallet; overflow throws instead of wrapping. Since the
 * U3 of 261113-trading-real-bot, the open reservations are also summed by kind ({@code order:} and {@code wd:}).
 */
public final class RealBalances {

    static final String ORDER_PREFIX = "order:";
    static final String WITHDRAWAL_PREFIX = "wd:";

    private RealBalances() {}

    public static RealBalance of(List<RealLedgerRow> rows) {
        long total = 0;
        long reserved = 0;
        boolean swapFills = false;
        Map<String, Long> openByReference = new HashMap<>();
        for (RealLedgerRow row : rows) {
            if (row.kind().isBalanceEntry()) {
                total = Math.addExact(total, row.lamports());
                swapFills |= row.kind() == RealEntryKind.SWAP_FILL_IN || row.kind() == RealEntryKind.SWAP_FILL_OUT;
            } else {
                long signed = row.kind() == RealEntryKind.RESERVE ? row.lamports() : Math.negateExact(row.lamports());
                reserved = Math.addExact(reserved, signed);
                openByReference.merge(row.reference(), signed, Math::addExact);
            }
        }
        long orders = 0;
        long withdrawals = 0;
        for (Map.Entry<String, Long> open : openByReference.entrySet()) {
            if (open.getValue() <= 0) {
                continue;
            }
            if (open.getKey().startsWith(ORDER_PREFIX)) {
                orders = Math.addExact(orders, open.getValue());
            } else if (open.getKey().startsWith(WITHDRAWAL_PREFIX)) {
                withdrawals = Math.addExact(withdrawals, open.getValue());
            }
        }
        Set<String> open = openByReference.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        return new RealBalance(
                total, reserved, Math.subtractExact(total, reserved), open, swapFills, orders, withdrawals);
    }
}
