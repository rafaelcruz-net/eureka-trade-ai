package com.memetrade.realexits.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U7, written BEFORE the code (BR7.1, BR7.2, BR7.4, BR7.5, S28, S29, S35, U7-NFR1.7, U7-NFR10.1): the
 * fiscal CSV is UTF-8 with BOM, separator {@code ;}, decimal comma and {@code \r\n}; numbers are written as numbers,
 * always positive, without prefix; the protection against formulas applies only to the text fields; an empty field
 * means no value, never zero; the dates in UTC and in the zone of the person.
 */
@Tag("u7-exits-and-fiscal")
class FiscalCsvWriterTest {

    private static final String MINT = FiscalRowMapperTest.MINT;
    private static final String SIG = FiscalRowMapperTest.SIG;
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final String HEADER =
            "data_utc;data_local;tipo;mint;simbolo;quantidade;preco_sol;valor_sol;taxa_sol;assinatura\r\n";

    private static String write(Map<String, Integer> decimals, Map<String, String> symbols, FiscalRow... rows)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FiscalCsvWriter writer = new FiscalCsvWriter(
                out,
                SAO_PAULO,
                mint -> Optional.ofNullable(decimals.get(mint)),
                mint -> Optional.ofNullable(symbols.get(mint)))) {
            writer.header();
            for (FiscalRow row : rows) {
                writer.write(row);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static FiscalRow buy() {
        return new FiscalRow(
                Instant.parse("2026-03-01T12:00:05Z"),
                FiscalKind.COMPRA,
                Optional.of(MINT),
                Optional.of(5_000_000L),
                Optional.of(50_000_000L),
                Optional.of(5_000L),
                Optional.of(SIG));
    }

    @Test
    void theFileStartsWithTheBomAndTheHeaderSeparatedBySemicolonsAndCrLf() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FiscalCsvWriter writer =
                new FiscalCsvWriter(out, SAO_PAULO, m -> Optional.empty(), m -> Optional.empty())) {
            writer.header();
        }
        byte[] bytes = out.toByteArray();

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                .isEqualTo(HEADER);
    }

    /** S28: one line with exact values, decimal comma, the price with the decimals of the mint plus 9. */
    @Test
    void s28ABuyIsWrittenWithExactValuesAndADecimalComma() throws IOException {
        String csv = write(Map.of(MINT, 6), Map.of(MINT, "BONK"), buy());

        assertThat(csv.substring(1))
                .isEqualTo(HEADER
                        + "2026-03-01T12:00:05Z;2026-03-01 09:00:05;COMPRA;" + MINT
                        + ";BONK;5,000000;0,010000000000000;"
                        + "0,050000000;0,000005000;" + SIG + "\r\n");
    }

    /** S35, BR7.4: a deposit has no quantity, price nor fee: empty fields, never zero. */
    @Test
    void s35AFieldWithoutValueIsEmptyNeverZero() throws IOException {
        FiscalRow deposit = new FiscalRow(
                Instant.parse("2026-03-01T03:00:00Z"),
                FiscalKind.DEPOSITO,
                Optional.empty(),
                Optional.empty(),
                Optional.of(3_000_000_000L),
                Optional.empty(),
                Optional.empty());

        assertThat(write(Map.of(), Map.of(), deposit))
                .endsWith("2026-03-01T03:00:00Z;2026-03-01 00:00:00;DEPOSITO;;;;;3,000000000;;\r\n");
    }

    /** S29, U7-NFR1.7: a text starting with =, +, -, @, tab or return gets an apostrophe first. */
    @Test
    void s29ATextThatLooksLikeAFormulaIsEscaped() {
        assertThat(FiscalCsvWriter.text("=HYPER")).isEqualTo("'=HYPER");
        assertThat(FiscalCsvWriter.text("+1")).isEqualTo("'+1");
        assertThat(FiscalCsvWriter.text("-SOL")).isEqualTo("'-SOL");
        assertThat(FiscalCsvWriter.text("@cmd")).isEqualTo("'@cmd");
        assertThat(FiscalCsvWriter.text("\tTAB")).isEqualTo("'\tTAB");
        assertThat(FiscalCsvWriter.text("\rCR")).isEqualTo("\"'\rCR\"");
        assertThat(FiscalCsvWriter.text("BONK")).isEqualTo("BONK");
    }

    /** A text with the separator, a quote or a line break is quoted, as CSV asks. */
    @Test
    void aTextWithTheSeparatorOrAQuoteIsQuoted() {
        assertThat(FiscalCsvWriter.text("A;B")).isEqualTo("\"A;B\"");
        assertThat(FiscalCsvWriter.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    /** BR7.5: numbers are written as numbers: positive, never with the apostrophe of the text fields. */
    @Test
    void numbersAreNeverEscapedAndAlwaysPositive() throws IOException {
        String csv = write(Map.of(MINT, 6), Map.of(MINT, "=EVIL"), buy());
        String line = csv.split("\r\n")[1];
        String[] fields = line.split(";", -1);

        assertThat(fields[4]).isEqualTo("'=EVIL");
        assertThat(fields[5]).doesNotStartWith("'").doesNotStartWith("-");
        assertThat(fields[7]).doesNotStartWith("'").doesNotStartWith("-");
        assertThat(fields[8]).doesNotStartWith("'").doesNotStartWith("-");
    }

    /** Without the decimals of the mint the quantity is in base units and the price stays empty. */
    @Test
    void withoutTheDecimalsOfTheMintTheQuantityIsInBaseUnitsAndThePriceIsEmpty() throws IOException {
        String line = write(Map.of(), Map.of(), buy()).split("\r\n")[1];
        String[] fields = line.split(";", -1);

        assertThat(fields[5]).isEqualTo("5000000");
        assertThat(fields[6]).isEmpty();
        assertThat(fields[4]).as("no symbol known: empty").isEmpty();
    }

    /** BR7.4: the price is the value over the quantity with the decimals of the mint plus 9, never rounded up. */
    @Test
    void thePriceIsNeverRoundedUp() {
        assertThat(FiscalCsvWriter.price(1L, 3L, 0)).isEqualTo("0,000000000");
        assertThat(FiscalCsvWriter.price(2_000_000_000L, 3L, 0)).isEqualTo("0,666666666");
    }

    /** U7-NFR10.1: the local column follows the zone of the person, across the change of the offset. */
    @Test
    void theLocalDateFollowsTheZoneOfThePerson() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FiscalCsvWriter writer =
                new FiscalCsvWriter(out, ZoneId.of("Asia/Tokyo"), m -> Optional.empty(), m -> Optional.empty())) {
            writer.write(new FiscalRow(
                    Instant.parse("2026-12-31T20:00:00Z"),
                    FiscalKind.TAXA,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(5_000L),
                    Optional.empty()));
        }

        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("2026-12-31T20:00:00Z;2027-01-01 05:00:00;TAXA;");
    }
}
