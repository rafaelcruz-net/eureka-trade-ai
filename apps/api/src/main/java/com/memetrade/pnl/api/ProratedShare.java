package com.memetrade.pnl.api;

import java.time.Instant;

/**
 * 261121-paper-real-unico U1, C10: one prorated sale of a bot (owner of the format: U2). The U1 never produces one; the
 * type exists so the signature of C10 is the definitive one.
 *
 * @param origin {@code LIQUIDATION}, {@code CONDITIONAL} or {@code SOLD_BY_OTHER_BOT}
 */
public record ProratedShare(
        String saleRef,
        String origin,
        String mint,
        Instant at,
        long tokenBaseUnits,
        long receivedLamports,
        long feeLamports,
        long realizedLamports) {}
