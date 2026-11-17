package com.memetrade.realexits.fiscal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of the fiscal CSV (BR7.2 to BR7.4) before its formatting: every amount is positive (the kind says the
 * direction) in lamports or base units; an empty value is an empty field, never zero.
 */
public record FiscalRow(
        Instant at,
        FiscalKind kind,
        Optional<String> mint,
        Optional<Long> quantityBaseUnits,
        Optional<Long> valueLamports,
        Optional<Long> feeLamports,
        Optional<String> signature) {

    public FiscalRow {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(quantityBaseUnits, "quantityBaseUnits");
        Objects.requireNonNull(valueLamports, "valueLamports");
        Objects.requireNonNull(feeLamports, "feeLamports");
        Objects.requireNonNull(signature, "signature");
    }
}
