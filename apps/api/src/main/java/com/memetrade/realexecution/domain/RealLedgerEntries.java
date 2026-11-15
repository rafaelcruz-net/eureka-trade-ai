package com.memetrade.realexecution.domain;

import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import java.util.ArrayList;
import java.util.List;

/**
 * BR6.1 (adendo da U3, convention of the fills): the entries of the REAL ledger of an order from the values read from
 * the network. Pure. The RESERVE_RELEASE is recorded by the transition of the order, not here.
 *
 * <ul>
 *   <li>CONFIRMED buy: SWAP_FILL_IN (minus the input in lamports, plus the tokens received), NETWORK_FEE when the bot
 *       wallet paid it (AUTO_FULL), RENT for what the bot wallet paid beyond the input and the fee (a created account);
 *   <li>CONFIRMED sell: SWAP_FILL_OUT (the lamports received, the tokens sold), NETWORK_FEE when the bot wallet paid;
 *   <li>FAILED on chain: only NETWORK_FEE when the bot wallet paid; CANCELLED or never on chain: nothing.
 * </ul>
 *
 * In SIGNED the fee leaves the main wallet: it never enters the ledger of the bot wallet (it stays on the order).
 */
public final class RealLedgerEntries {

    /** One entry: kind, signed lamports and, for a fill, the positive quantity of the token. */
    public record Entry(RealEntryKind kind, long lamports, Long tokenBaseUnits) {}

    private RealLedgerEntries() {}

    /**
     * @param input the lamports of a buy, or the base units of a sell
     * @param botLamportsDelta post minus pre lamports of the bot wallet (fee and rent included)
     * @param tokenDelta post minus pre balance of the token of the bot wallet
     */
    public static List<Entry> of(
            RealSide side,
            RealOrderState to,
            long input,
            long botLamportsDelta,
            long tokenDelta,
            long feeLamports,
            boolean feePaidByBot) {
        List<Entry> entries = new ArrayList<>();
        long botFee = feePaidByBot ? feeLamports : 0;
        if (to == RealOrderState.CONFIRMED && side == RealSide.BUY) {
            if (tokenDelta > 0) {
                entries.add(new Entry(RealEntryKind.SWAP_FILL_IN, -input, tokenDelta));
            }
            addFee(entries, botFee);
            long rent = rentOf(side, to, input, botLamportsDelta, tokenDelta, feeLamports, feePaidByBot);
            if (rent > 0) {
                entries.add(new Entry(RealEntryKind.RENT, -rent, null));
            }
        } else if (to == RealOrderState.CONFIRMED) {
            long received = Math.addExact(botLamportsDelta, botFee);
            long sold = -tokenDelta;
            if (received > 0 && sold > 0) {
                entries.add(new Entry(RealEntryKind.SWAP_FILL_OUT, received, sold));
            }
            addFee(entries, botFee);
        } else if (to == RealOrderState.FAILED) {
            addFee(entries, botFee);
        }
        return entries;
    }

    /**
     * The rent the bot wallet paid for an account a confirmed buy created (the RENT entry), or 0: what the bot wallet
     * spent beyond the input and the fee it paid. The same number goes on the order for the P&L (review, Minor 6).
     */
    public static long rentOf(
            RealSide side,
            RealOrderState to,
            long input,
            long botLamportsDelta,
            long tokenDelta,
            long feeLamports,
            boolean feePaidByBot) {
        if (to != RealOrderState.CONFIRMED || side != RealSide.BUY || tokenDelta <= 0) {
            return 0;
        }
        long botFee = feePaidByBot ? feeLamports : 0;
        return Math.max(0, Math.subtractExact(Math.subtractExact(-botLamportsDelta, input), botFee));
    }

    private static void addFee(List<Entry> entries, long botFee) {
        if (botFee > 0) {
            entries.add(new Entry(RealEntryKind.NETWORK_FEE, -botFee, null));
        }
    }
}
