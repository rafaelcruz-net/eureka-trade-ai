package com.memetrade.realexits.fiscal;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;

/**
 * BR7.1, BR7.2, BR7.4, BR7.5, U7-NFR1.7, U7-NFR8.1: the fiscal CSV, written line by line on the output of the response
 * (U7-NFR3.3), with no library: UTF-8 with BOM, separator {@code ;}, decimal comma, {@code \r\n}. Numbers are written as
 * numbers ({@link BigDecimal#toPlainString()} with a comma), always positive and never escaped; only the text fields
 * get the protection against formulas (an apostrophe before {@code = + - @}, tab or return) and the quotes of CSV. SOL
 * values have 9 decimals; quantities the decimals of the mint (base units when unknown); the price in SOL has the
 * decimals of the mint plus 9, never rounded up, and is empty without a quantity or without the decimals. An empty field
 * is no value, never zero.
 */
public final class FiscalCsvWriter implements Closeable {

    static final String HEADER =
            "data_utc;data_local;tipo;mint;simbolo;quantidade;preco_sol;valor_sol;taxa_sol;assinatura";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String EOL = "\r\n";
    private static final int SOL_DECIMALS = 9;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OutputStream raw;
    private final Writer out;
    private final ZoneId zone;
    private final Function<String, Optional<Integer>> decimals;
    private final Function<String, Optional<String>> symbols;
    private boolean bomWritten;

    /**
     * @param decimals the decimals of a mint (M4), empty when unknown
     * @param symbols the symbol of a mint (M4), empty when unknown
     */
    public FiscalCsvWriter(
            OutputStream out,
            ZoneId zone,
            Function<String, Optional<Integer>> decimals,
            Function<String, Optional<String>> symbols) {
        this.raw = out;
        this.out = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        this.zone = zone;
        this.decimals = decimals;
        this.symbols = symbols;
    }

    /** The BOM and the header (BR7.1, BR7.2). */
    public void header() {
        writeBom();
        line(HEADER);
    }

    /** One line, flushed at once to the output of the response (the first line leaves before the last is read). */
    public void write(FiscalRow row) {
        Optional<Integer> mintDecimals = row.mint().flatMap(decimals);
        StringBuilder text = new StringBuilder()
                .append(row.at().truncatedTo(ChronoUnit.SECONDS))
                .append(';')
                .append(LOCAL.format(row.at().atZone(zone)))
                .append(';')
                .append(row.kind().name())
                .append(';')
                .append(row.mint().orElse(""))
                .append(';')
                .append(row.mint().flatMap(symbols).map(FiscalCsvWriter::text).orElse(""))
                .append(';')
                .append(row.quantityBaseUnits()
                        .map(units -> quantity(units, mintDecimals))
                        .orElse(""))
                .append(';')
                .append(priceOf(row, mintDecimals))
                .append(';')
                .append(row.valueLamports().map(FiscalCsvWriter::sol).orElse(""))
                .append(';')
                .append(row.feeLamports().map(FiscalCsvWriter::sol).orElse(""))
                .append(';')
                .append(row.signature().map(FiscalCsvWriter::text).orElse(""));
        line(text.toString());
    }

    @Override
    public void close() {
        try {
            out.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("The fiscal CSV could not be written", failure);
        }
    }

    private void writeBom() {
        if (bomWritten) {
            return;
        }
        bomWritten = true;
        try {
            raw.write(BOM);
        } catch (IOException failure) {
            throw new UncheckedIOException("The fiscal CSV could not be written", failure);
        }
    }

    private void line(String text) {
        try {
            out.write(text);
            out.write(EOL);
            out.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("The fiscal CSV could not be written", failure);
        }
    }

    /** BR7.5: a text field; a leading = + - @, tab or return gets an apostrophe; ; " and line breaks are quoted. */
    static String text(String value) {
        String safe = value;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            safe = "'" + value;
        }
        if (safe.indexOf(';') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    /** Lamports as SOL with 9 decimals and a decimal comma, exact. */
    static String sol(long lamports) {
        return number(BigDecimal.valueOf(lamports, SOL_DECIMALS));
    }

    private static String quantity(long units, Optional<Integer> mintDecimals) {
        return mintDecimals
                .map(scale -> number(BigDecimal.valueOf(units, scale)))
                .orElse(Long.toString(units));
    }

    private static String priceOf(FiscalRow row, Optional<Integer> mintDecimals) {
        if (row.quantityBaseUnits().isEmpty()
                || row.valueLamports().isEmpty()
                || mintDecimals.isEmpty()
                || row.quantityBaseUnits().get() == 0) {
            return "";
        }
        return price(row.valueLamports().get(), row.quantityBaseUnits().get(), mintDecimals.get());
    }

    /** BR7.4: {@code valor_sol / quantidade} with the decimals of the mint plus 9, rounded down. */
    static String price(long lamports, long units, int mintDecimals) {
        BigDecimal value = BigDecimal.valueOf(lamports, SOL_DECIMALS);
        BigDecimal quantity = BigDecimal.valueOf(units, mintDecimals);
        return number(value.divide(quantity, mintDecimals + SOL_DECIMALS, RoundingMode.DOWN));
    }

    private static String number(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }
}
