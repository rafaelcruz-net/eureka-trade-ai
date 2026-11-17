package com.memetrade.realexits.fiscal;

import java.util.Collection;

/**
 * BR7.6: the sum of the lines of the fiscal CSV in lamports, which equals the variation of the SOL balance of the REAL
 * ledger (reserves excluded) in the same period: DEPOSITO - SAQUE - taxa do saque + VENDA + VENDA_CONDICIONAL - COMPRA -
 * taxas das ordens - TAXA + AJUSTE_ENTRADA - AJUSTE_SAIDA - ALUGUEL_CONTA. An adjustment of a token moves no SOL.
 */
public final class FiscalBalance {

    private FiscalBalance() {}

    public static long solDeltaLamports(Collection<FiscalRow> rows) {
        long sum = 0;
        for (FiscalRow row : rows) {
            sum = Math.addExact(sum, signedLamports(row));
        }
        return sum;
    }

    /** The effect of one line on the SOL of the bot wallet, with the sign of the rule. */
    static long signedLamports(FiscalRow row) {
        long value = row.valueLamports().orElse(0L);
        long fee = row.feeLamports().orElse(0L);
        return switch (row.kind()) {
            case DEPOSITO, VENDA, VENDA_CONDICIONAL, AJUSTE_ENTRADA -> Math.subtractExact(value, fee);
            case SAQUE, COMPRA, TAXA, AJUSTE_SAIDA, ALUGUEL_CONTA -> Math.negateExact(Math.addExact(value, fee));
        };
    }
}
