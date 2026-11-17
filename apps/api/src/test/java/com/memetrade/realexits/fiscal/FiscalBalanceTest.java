package com.memetrade.realexits.fiscal;

import static com.memetrade.realexits.fiscal.FiscalRowMapperTest.entry;
import static com.memetrade.realexits.fiscal.FiscalRowMapperTest.fill;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealEntryRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U7, written BEFORE the code (BR7.6, S45): on a sample ledger with every kind of entry, the sum of the
 * lines of the CSV ({@code DEPOSITO - SAQUE - taxa do saque + VENDA + VENDA_CONDICIONAL - COMPRA - taxas das ordens -
 * TAXA + AJUSTE_ENTRADA - AJUSTE_SAIDA - ALUGUEL_CONTA}) equals the variation of the SOL balance of the REAL ledger
 * (reserves excluded) in the period.
 */
@Tag("u7-exits-and-fiscal")
class FiscalBalanceTest {

    /** Every kind of entry, including the reserves (which never count) and a compensation of a token. */
    private static List<RealEntryRecord> sampleLedger() {
        return List.of(
                entry("dep:a", RealEntryKind.DEPOSIT, 3_000_000_000L),
                entry("order:1", RealEntryKind.RESERVE, 500_000_000L),
                fill("order:1", RealEntryKind.SWAP_FILL_IN, -500_000_000L, 9_000_000L),
                entry("order:1", RealEntryKind.NETWORK_FEE, -5_000L),
                entry("order:1", RealEntryKind.RENT, -2_039_280L),
                entry("order:1", RealEntryKind.RESERVE_RELEASE, 500_000_000L),
                fill("order:2", RealEntryKind.SWAP_FILL_OUT, 320_000_000L, 4_000_000L),
                entry("order:2", RealEntryKind.NETWORK_FEE, -5_000L),
                fill("conditional:3", RealEntryKind.SWAP_FILL_OUT, 410_000_000L, 5_000_000L),
                entry("conditional:3", RealEntryKind.NETWORK_FEE, -7_500L),
                entry("order:4", RealEntryKind.NETWORK_FEE, -5_000L),
                entry("comp:1", RealEntryKind.COMPENSATION, 12_345L),
                entry("comp:2", RealEntryKind.COMPENSATION, -2_000L),
                new RealEntryRecord(
                        RealEntryKind.COMPENSATION,
                        0L,
                        Optional.of(FiscalRowMapperTest.MINT),
                        Optional.of(3L),
                        Optional.empty(),
                        "comp:3",
                        FiscalRowMapperTest.AT),
                entry("wd:5", RealEntryKind.RESERVE, 1_000_000_000L),
                entry("wd:5", RealEntryKind.WITHDRAWAL, -1_000_000_000L),
                entry("wd:5", RealEntryKind.NETWORK_FEE, -5_000L),
                entry("wd:5", RealEntryKind.RESERVE_RELEASE, 1_000_000_000L));
    }

    @Test
    void s45TheSumOfTheLinesIsTheVariationOfTheSolBalance() {
        List<FiscalRow> rows = new ArrayList<>();
        FiscalRowMapper mapper = new FiscalRowMapper(rows::add);
        sampleLedger().forEach(mapper::accept);
        mapper.finish();
        long balanceVariation = sampleLedger().stream()
                .filter(entry -> entry.kind().isBalanceEntry())
                .mapToLong(RealEntryRecord::lamports)
                .sum();

        assertThat(FiscalBalance.solDeltaLamports(rows)).isEqualTo(balanceVariation);
        assertThat(balanceVariation).isEqualTo(2_227_943_565L);
    }

    @Test
    void anEmptyPeriodVariesNothing() {
        assertThat(FiscalBalance.solDeltaLamports(List.of())).isZero();
    }

    @Test
    void eachKindCountsWithTheSignOfTheRule() {
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.DEPOSITO, 10L, null)))
                .isEqualTo(10L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.SAQUE, 10L, 2L))).isEqualTo(-12L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.VENDA, 10L, 2L))).isEqualTo(8L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.VENDA_CONDICIONAL, 10L, null)))
                .isEqualTo(10L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.COMPRA, 10L, 2L)))
                .isEqualTo(-12L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.TAXA, null, 2L))).isEqualTo(-2L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.AJUSTE_ENTRADA, 10L, null)))
                .isEqualTo(10L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.AJUSTE_SAIDA, 10L, null)))
                .isEqualTo(-10L);
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.AJUSTE_SAIDA, null, null)))
                .as("an adjustment of a token moves no SOL")
                .isZero();
        assertThat(FiscalBalance.signedLamports(row(FiscalKind.ALUGUEL_CONTA, 10L, null)))
                .isEqualTo(-10L);
    }

    private static FiscalRow row(FiscalKind kind, Long value, Long fee) {
        return new FiscalRow(
                FiscalRowMapperTest.AT,
                kind,
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(value),
                Optional.ofNullable(fee),
                Optional.empty());
    }
}
