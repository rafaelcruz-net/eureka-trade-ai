package com.memetrade.realexits.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealEntryRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U7, written BEFORE the code (BR7.3, BR7.4, S28, S35, S46): the entries of the REAL ledger of one
 * reference become the lines of the fiscal CSV. The fee of an order or of a withdrawal goes only in {@code taxa_sol} of
 * its line, never in a separate TAXA line; the reserves never enter; every value is positive and the kind says the
 * direction; a missing value is empty, never zero.
 */
@Tag("u7-exits-and-fiscal")
class FiscalRowMapperTest {

    static final String MINT = "Mint1111111111111111111111111111111111111111";
    static final String SIG = "5".repeat(88);
    static final Instant AT = Instant.parse("2026-03-01T12:00:00Z");

    static RealEntryRecord entry(String reference, RealEntryKind kind, long lamports) {
        return new RealEntryRecord(kind, lamports, Optional.empty(), Optional.empty(), Optional.of(SIG), reference, AT);
    }

    static RealEntryRecord fill(String reference, RealEntryKind kind, long lamports, long units) {
        return new RealEntryRecord(
                kind, lamports, Optional.of(MINT), Optional.of(units), Optional.of(SIG), reference, AT);
    }

    private static List<FiscalRow> map(RealEntryRecord... entries) {
        List<FiscalRow> rows = new ArrayList<>();
        FiscalRowMapper mapper = new FiscalRowMapper(rows::add);
        for (RealEntryRecord entry : entries) {
            mapper.accept(entry);
        }
        mapper.finish();
        return rows;
    }

    @Test
    void aBuyIsOneCompraLineWithItsFeeAndTheRentOnItsOwnLine() {
        List<FiscalRow> rows = map(
                entry("order:1", RealEntryKind.RESERVE, 50_000_000L),
                fill("order:1", RealEntryKind.SWAP_FILL_IN, -50_000_000L, 5_000L),
                entry("order:1", RealEntryKind.NETWORK_FEE, -5_000L),
                entry("order:1", RealEntryKind.RENT, -2_039_280L),
                entry("order:1", RealEntryKind.RESERVE_RELEASE, 50_000_000L));

        assertThat(rows)
                .containsExactly(
                        new FiscalRow(
                                AT,
                                FiscalKind.COMPRA,
                                Optional.of(MINT),
                                Optional.of(5_000L),
                                Optional.of(50_000_000L),
                                Optional.of(5_000L),
                                Optional.of(SIG)),
                        new FiscalRow(
                                AT,
                                FiscalKind.ALUGUEL_CONTA,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(2_039_280L),
                                Optional.empty(),
                                Optional.of(SIG)));
    }

    /** S46: the fee of a sale is only in its taxa_sol; no TAXA line repeats it. */
    @Test
    void s46TheFeeOfASaleIsOnlyInItsLine() {
        List<FiscalRow> rows = map(
                fill("order:2", RealEntryKind.SWAP_FILL_OUT, 60_000_000L, 5_000L),
                entry("order:2", RealEntryKind.NETWORK_FEE, -5_000L));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).kind()).isEqualTo(FiscalKind.VENDA);
        assertThat(rows.get(0).valueLamports()).contains(60_000_000L);
        assertThat(rows.get(0).feeLamports()).contains(5_000L);
        assertThat(rows).noneMatch(row -> row.kind() == FiscalKind.TAXA);
    }

    @Test
    void theFillOfAConditionalIsAVendaCondicional() {
        List<FiscalRow> rows = map(
                fill("conditional:9", RealEntryKind.SWAP_FILL_OUT, 1_200_000L, 5_000L),
                entry("conditional:9", RealEntryKind.NETWORK_FEE, -5_000L));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(FiscalKind.VENDA_CONDICIONAL);
            assertThat(row.quantityBaseUnits()).contains(5_000L);
            assertThat(row.feeLamports()).contains(5_000L);
        });
    }

    /** S35: a deposit has no quantity, no price and no fee: empty, never zero. */
    @Test
    void s35ADepositHasOnlyItsValue() {
        assertThat(map(entry("dep:" + SIG, RealEntryKind.DEPOSIT, 3_000_000_000L)))
                .containsExactly(new FiscalRow(
                        AT,
                        FiscalKind.DEPOSITO,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(3_000_000_000L),
                        Optional.empty(),
                        Optional.of(SIG)));
    }

    @Test
    void aWithdrawalCarriesItsFee() {
        assertThat(map(
                        entry("wd:7", RealEntryKind.WITHDRAWAL, -1_000_000_000L),
                        entry("wd:7", RealEntryKind.NETWORK_FEE, -5_000L)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.kind()).isEqualTo(FiscalKind.SAQUE);
                    assertThat(row.valueLamports()).contains(1_000_000_000L);
                    assertThat(row.feeLamports()).contains(5_000L);
                });
    }

    /** A fee without an order fill nor a withdrawal (an order failed on chain) is a TAXA line. */
    @Test
    void aFeeAloneIsATaxaLine() {
        assertThat(map(entry("order:3", RealEntryKind.NETWORK_FEE, -5_000L)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.kind()).isEqualTo(FiscalKind.TAXA);
                    assertThat(row.valueLamports()).isEmpty();
                    assertThat(row.feeLamports()).contains(5_000L);
                });
    }

    /** BR7.4: the direction of an adjustment is in the kind; the value or the quantity is positive. */
    @Test
    void anAdjustmentSaysItsDirectionInTheKind() {
        List<FiscalRow> rows = map(
                entry("comp:1", RealEntryKind.COMPENSATION, 1_000L),
                entry("comp:2", RealEntryKind.COMPENSATION, -2_000L),
                new RealEntryRecord(
                        RealEntryKind.COMPENSATION,
                        0L,
                        Optional.of(MINT),
                        Optional.of(-7L),
                        Optional.empty(),
                        "comp:3",
                        AT));

        assertThat(rows)
                .extracting(FiscalRow::kind)
                .containsExactly(FiscalKind.AJUSTE_ENTRADA, FiscalKind.AJUSTE_SAIDA, FiscalKind.AJUSTE_SAIDA);
        assertThat(rows.get(1).valueLamports()).contains(2_000L);
        assertThat(rows.get(2).valueLamports()).isEmpty();
        assertThat(rows.get(2).quantityBaseUnits()).contains(7L);
        assertThat(rows.get(2).mint()).contains(MINT);
    }

    /** BR7.3: the reserves are internal; a reference with only reserves gives no line. */
    @Test
    void theReservesNeverEnter() {
        assertThat(map(
                        entry("order:4", RealEntryKind.RESERVE, 1_000L),
                        entry("order:4", RealEntryKind.RESERVE_RELEASE, 1_000L)))
                .isEmpty();
    }

    /** One line per operation: the references are grouped as they come, adjacent. */
    @Test
    void eachReferenceGivesItsOwnLines() {
        List<FiscalRow> rows = map(
                entry("dep:a", RealEntryKind.DEPOSIT, 1_000L),
                fill("order:5", RealEntryKind.SWAP_FILL_IN, -500L, 10L),
                fill("order:6", RealEntryKind.SWAP_FILL_OUT, 700L, 10L));

        assertThat(rows)
                .extracting(FiscalRow::kind)
                .containsExactly(FiscalKind.DEPOSITO, FiscalKind.COMPRA, FiscalKind.VENDA);
    }

    @Test
    void theSignatureOfADepositComesFromItsReferenceWhenTheEntryHasNone() {
        RealEntryRecord deposit = new RealEntryRecord(
                RealEntryKind.DEPOSIT, 1_000L, Optional.empty(), Optional.empty(), Optional.empty(), "dep:" + SIG, AT);

        assertThat(map(deposit)).singleElement().satisfies(row -> assertThat(row.signature())
                .contains(SIG));
    }
}
