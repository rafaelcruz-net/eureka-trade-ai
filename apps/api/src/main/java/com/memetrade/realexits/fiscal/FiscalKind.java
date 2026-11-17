package com.memetrade.realexits.fiscal;

/** BR7.3, BR7.4: the {@code tipo} of a line of the fiscal CSV; the direction of the operation is in the kind. */
public enum FiscalKind {
    COMPRA,
    VENDA,
    VENDA_CONDICIONAL,
    DEPOSITO,
    SAQUE,
    TAXA,
    ALUGUEL_CONTA,
    AJUSTE_ENTRADA,
    AJUSTE_SAIDA
}
