package com.memetrade.botwallet.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 261113-trading-real-bot U5 (BR2.1, Q1, logical-components.md ReconciliationPolicy): the pure reconciliation of one bot
 * wallet, in this order:
 *
 * <ol>
 *   <li>an order or a withdrawal in transit: DEFERRED (the network is not even read);
 *   <li>the network did not answer: UNAVAILABLE (never a divergence nor a pause, U5-NFR4.3);
 *   <li>an entry of the network newer than the cursor of the deposit scan: DEFERRED (not yet registered);
 *   <li>261113-trading-real-bot U7 (U7-NFR4.8): a custody account of an open conditional not read: UNAVAILABLE;
 *   <li>otherwise the lamports and every mint against the REAL ledger with ZERO tolerance: CLEAN or DIVERGENT.
 * </ol>
 *
 * The ledger already records the real values read from the network for every confirmed order (swap, fee paid by the bot
 * wallet, rent and its refund, wrapped SOL); in the SIGNED mode the fee is paid by the main wallet and touches neither
 * side. Only the REAL ledger is compared, never PAPER (U5-NFR2.1).
 */
public final class ReconciliationPolicy {

    private ReconciliationPolicy() {}

    public static ReconciliationOutcome decide(ReconciliationInputs in) {
        if (in.inTransit() > 0) {
            return ReconciliationOutcome.notCompared(ReconciliationResult.DEFERRED, in.inTransit());
        }
        if (in.chain().isEmpty()) {
            return ReconciliationOutcome.notCompared(ReconciliationResult.UNAVAILABLE, 0);
        }
        ChainReading reading = in.chain().get();
        if (reading.unreadIncoming()) {
            return ReconciliationOutcome.notCompared(ReconciliationResult.DEFERRED, 0);
        }
        if (in.escrows().isEmpty()) {
            // U7 (U7-NFR4.8): a custody account of an open conditional could not be read: the network was not read.
            return ReconciliationOutcome.notCompared(ReconciliationResult.UNAVAILABLE, 0);
        }
        ChainSnapshot chain = withEscrows(reading.snapshot(), in.escrows().get());
        List<TokenDiff> diffs = tokenDiffs(chain, in.ledger());
        boolean equal = chain.lamports() == in.ledger().lamports() && diffs.isEmpty();
        return new ReconciliationOutcome(
                equal ? ReconciliationResult.CLEAN : ReconciliationResult.DIVERGENT,
                chain.slot(),
                chain.lamports(),
                in.ledger().lamports(),
                diffs,
                0);
    }

    /**
     * 261113-trading-real-bot U7 (BR5.3b): the token of an open conditional of the Jupiter is in the custody account of
     * the order; it still belongs to the bot wallet, so it is added to the mint read in the wallet.
     */
    private static ChainSnapshot withEscrows(ChainSnapshot chain, Map<String, Long> escrows) {
        if (escrows.isEmpty()) {
            return chain;
        }
        Map<String, Long> tokens = new java.util.TreeMap<>(chain.tokens());
        escrows.forEach((mint, units) -> tokens.merge(mint, units, Math::addExact));
        return new ChainSnapshot(chain.slot(), chain.lamports(), tokens);
    }

    private static List<TokenDiff> tokenDiffs(ChainSnapshot chain, LedgerSnapshot ledger) {
        TreeSet<String> mints = new TreeSet<>(chain.tokens().keySet());
        mints.addAll(ledger.tokens().keySet());
        List<TokenDiff> diffs = new ArrayList<>();
        for (String mint : mints) {
            TokenDiff diff = new TokenDiff(
                    mint, chain.tokens().getOrDefault(mint, 0L), ledger.tokens().getOrDefault(mint, 0L));
            if (diff.diff() != 0) {
                diffs.add(diff);
            }
        }
        return diffs;
    }
}
