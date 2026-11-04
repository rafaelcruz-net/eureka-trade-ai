package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.PaperEnums.EntryType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The entries of each fill (BR2.2), of the FUNDING (BR2.6) and of a close (BR5.1). A zero entry is never written.
 * Pure. The sum of a buy is {@code -(gross + fee)}; of a sell {@code gross - fee}; of a close {@code value - fee}.
 */
public final class LedgerEntries {

    private LedgerEntries() {}

    /** An entry to be written: type and signed USDC amount (scale 6), never zero. */
    public record Draft(EntryType type, BigDecimal amount) {}

    /** TRADE_COST = -money(qty x reference); SLIPPAGE = -(gross - |TRADE_COST|); FEE = -fee. */
    public static List<Draft> forBuy(FillQuote.Computed quote) {
        List<Draft> drafts = new ArrayList<>();
        add(drafts, EntryType.TRADE_COST, quote.referenceAmount().negate());
        add(
                drafts,
                EntryType.SLIPPAGE,
                quote.grossAmount().subtract(quote.referenceAmount()).negate());
        add(drafts, EntryType.FEE, quote.fee().negate());
        return List.copyOf(drafts);
    }

    /** TRADE_PROCEEDS = +money(qty x reference); SLIPPAGE = -(|TRADE_PROCEEDS| - gross); FEE = -fee. */
    public static List<Draft> forSell(FillQuote.Computed quote) {
        List<Draft> drafts = new ArrayList<>();
        add(drafts, EntryType.TRADE_PROCEEDS, quote.referenceAmount());
        add(
                drafts,
                EntryType.SLIPPAGE,
                quote.referenceAmount().subtract(quote.grossAmount()).negate());
        add(drafts, EntryType.FEE, quote.fee().negate());
        return List.copyOf(drafts);
    }

    /** CLOSE = +value; FEE = -fee on the value (BR5.1). */
    public static List<Draft> forClose(FillQuote.Computed quote) {
        List<Draft> drafts = new ArrayList<>();
        add(drafts, EntryType.CLOSE, quote.grossAmount());
        add(drafts, EntryType.FEE, quote.fee().negate());
        return List.copyOf(drafts);
    }

    /** FUNDING = +amount, greater than zero with at most 6 decimals (BR2.6). */
    public static Draft forFunding(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("The FUNDING amount is greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > Scales.MONEY) {
            throw new IllegalArgumentException("The FUNDING amount has at most 6 decimals");
        }
        return new Draft(EntryType.FUNDING, amount.setScale(Scales.MONEY));
    }

    private static void add(List<Draft> drafts, EntryType type, BigDecimal amount) {
        if (amount.signum() != 0) {
            drafts.add(new Draft(type, Scales.money(amount)));
        }
    }
}
