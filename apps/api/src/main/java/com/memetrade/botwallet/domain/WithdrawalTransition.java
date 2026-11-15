package com.memetrade.botwallet.domain;

import com.memetrade.ledger.api.RealEntry;
import java.util.List;

/** A withdrawal after one step and the REAL ledger entries to write in the same transaction (BR4.7). */
public record WithdrawalTransition(Withdrawal withdrawal, List<RealEntry> entries) {

    public WithdrawalTransition {
        entries = List.copyOf(entries);
    }

    public static WithdrawalTransition unchanged(Withdrawal withdrawal) {
        return new WithdrawalTransition(withdrawal, List.of());
    }
}
