package com.memetrade.botwallet.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port of the withdrawal follow-up of the worker (reliability-design.md "Trabalhos"). */
public interface WithdrawalStore {

    /**
     * Claims up to {@code limit} unfinished withdrawals whose nextAttemptAt passed, moving their nextAttemptAt by
     * {@code lease} so another worker instance skips them (SKIP LOCKED); returns them.
     */
    List<Withdrawal> claimReady(Instant now, Duration lease, int limit);

    /** The bot wallet and the registered main wallet of the withdrawal, read with its tenant (BR6.4). */
    Optional<WithdrawalTarget> target(Withdrawal withdrawal);

    /** Writes the new state, its REAL ledger entries and its audit event in one transaction (BR4.7, BR6.1). */
    void save(WithdrawalTransition transition);

    record WithdrawalTarget(String botWalletAddress, String mainWalletAddress) {}
}
