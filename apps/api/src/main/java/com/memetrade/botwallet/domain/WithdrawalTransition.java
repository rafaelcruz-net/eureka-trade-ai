package com.memetrade.botwallet.domain;

import com.memetrade.ledger.api.RealEntry;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A withdrawal after one step and the REAL ledger entries to write in the same transaction (BR4.7).
 *
 * @param slot 261121-paper-real-unico U2 (BR1.4): on a confirmation, the slot of the signature of the withdrawal read
 *     with its status; the store records it with the entries. Empty on every other step or when not known.
 */
public record WithdrawalTransition(Withdrawal withdrawal, List<RealEntry> entries, OptionalLong slot) {

    public WithdrawalTransition {
        entries = List.copyOf(entries);
        Objects.requireNonNull(slot, "slot");
    }

    public WithdrawalTransition(Withdrawal withdrawal, List<RealEntry> entries) {
        this(withdrawal, entries, OptionalLong.empty());
    }

    public static WithdrawalTransition unchanged(Withdrawal withdrawal) {
        return new WithdrawalTransition(withdrawal, List.of());
    }
}
