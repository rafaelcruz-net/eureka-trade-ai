package com.memetrade.realexits.fiscal;

import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealEntryRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * BR7.3: the entries of the REAL ledger, grouped by their reference (adjacent in the stream of M2), become the lines of
 * the fiscal CSV. Only the entries of one reference are held in memory at a time.
 *
 * <ul>
 *   <li>the fill of an order: COMPRA or VENDA (VENDA_CONDICIONAL for {@code conditional:}), with the fee of the order
 *       in {@code taxa_sol} of that line;
 *   <li>a withdrawal: SAQUE with its fee; a deposit: DEPOSITO; the rent of an account: ALUGUEL_CONTA;
 *   <li>a compensation: AJUSTE_ENTRADA or AJUSTE_SAIDA, in SOL or in the token;
 *   <li>a fee alone (an order failed on the network): TAXA;
 *   <li>the reserves never enter.
 * </ul>
 *
 * So a fee linked to an order or a withdrawal is never counted twice (S46).
 */
public final class FiscalRowMapper {

    private final Consumer<FiscalRow> out;
    private final List<RealEntryRecord> group = new ArrayList<>();

    public FiscalRowMapper(Consumer<FiscalRow> out) {
        this.out = out;
    }

    public void accept(RealEntryRecord entry) {
        if (!group.isEmpty() && !group.getFirst().reference().equals(entry.reference())) {
            flush();
        }
        group.add(entry);
    }

    /** The last group of the stream. */
    public void finish() {
        if (!group.isEmpty()) {
            flush();
        }
    }

    private void flush() {
        rowsOf(List.copyOf(group)).forEach(out);
        group.clear();
    }

    static List<FiscalRow> rowsOf(List<RealEntryRecord> entries) {
        List<FiscalRow> rows = new ArrayList<>();
        String reference = entries.getFirst().reference();
        long fees = entries.stream()
                .filter(entry -> entry.kind() == RealEntryKind.NETWORK_FEE)
                .mapToLong(entry -> Math.negateExact(entry.lamports()))
                .sum();
        Optional<Long> fee = fees > 0 ? Optional.of(fees) : Optional.empty();
        boolean feePlaced = false;
        for (RealEntryRecord entry : entries) {
            Optional<FiscalRow> row = primary(entry, reference, feePlaced ? Optional.empty() : fee, entries);
            if (row.isPresent()) {
                feePlaced = feePlaced || isPrimary(entry.kind());
                rows.add(row.get());
            }
        }
        if (!feePlaced && fee.isPresent()) {
            rows.add(new FiscalRow(
                    entries.getFirst().createdAt(),
                    FiscalKind.TAXA,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    fee,
                    signatureOf(entries.getFirst(), entries)));
        }
        return rows;
    }

    private static boolean isPrimary(RealEntryKind kind) {
        return kind == RealEntryKind.SWAP_FILL_IN
                || kind == RealEntryKind.SWAP_FILL_OUT
                || kind == RealEntryKind.WITHDRAWAL;
    }

    private static Optional<FiscalRow> primary(
            RealEntryRecord entry, String reference, Optional<Long> fee, List<RealEntryRecord> group) {
        Optional<String> signature = signatureOf(entry, group);
        long amount = Math.abs(entry.lamports());
        return switch (entry.kind()) {
            case SWAP_FILL_IN ->
                Optional.of(new FiscalRow(
                        entry.createdAt(),
                        FiscalKind.COMPRA,
                        entry.mint(),
                        entry.tokenBaseUnits().map(Math::abs),
                        Optional.of(amount),
                        fee,
                        signature));
            case SWAP_FILL_OUT ->
                Optional.of(new FiscalRow(
                        entry.createdAt(),
                        reference.startsWith("conditional:") ? FiscalKind.VENDA_CONDICIONAL : FiscalKind.VENDA,
                        entry.mint(),
                        entry.tokenBaseUnits().map(Math::abs),
                        Optional.of(amount),
                        fee,
                        signature));
            case WITHDRAWAL ->
                Optional.of(new FiscalRow(
                        entry.createdAt(),
                        FiscalKind.SAQUE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(amount),
                        fee,
                        signature));
            case DEPOSIT ->
                Optional.of(new FiscalRow(
                        entry.createdAt(),
                        FiscalKind.DEPOSITO,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(amount),
                        Optional.empty(),
                        signature));
            case RENT ->
                Optional.of(new FiscalRow(
                        entry.createdAt(),
                        FiscalKind.ALUGUEL_CONTA,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(amount),
                        Optional.empty(),
                        signature));
            case COMPENSATION -> Optional.of(adjustment(entry, signature));
            case NETWORK_FEE, RESERVE, RESERVE_RELEASE -> Optional.empty();
        };
    }

    /** BR7.4: an adjustment of SOL carries its value; of a token, its quantity and mint; the sign is in the kind. */
    private static FiscalRow adjustment(RealEntryRecord entry, Optional<String> signature) {
        long signed = entry.lamports() != 0
                ? entry.lamports()
                : entry.tokenBaseUnits().orElse(0L);
        FiscalKind kind = signed >= 0 ? FiscalKind.AJUSTE_ENTRADA : FiscalKind.AJUSTE_SAIDA;
        if (entry.lamports() != 0) {
            return new FiscalRow(
                    entry.createdAt(),
                    kind,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Math.abs(entry.lamports())),
                    Optional.empty(),
                    signature);
        }
        return new FiscalRow(
                entry.createdAt(),
                kind,
                entry.mint(),
                entry.tokenBaseUnits().map(Math::abs),
                Optional.empty(),
                Optional.empty(),
                signature);
    }

    /** The signature of the entry, of another entry of the operation, or the one in the reference of a deposit. */
    private static Optional<String> signatureOf(RealEntryRecord entry, List<RealEntryRecord> group) {
        if (entry.txSignature().isPresent()) {
            return entry.txSignature();
        }
        Optional<String> other = group.stream()
                .map(RealEntryRecord::txSignature)
                .flatMap(Optional::stream)
                .findFirst();
        if (other.isPresent()) {
            return other;
        }
        String reference = entry.reference();
        return reference.startsWith("dep:") && reference.length() > 70
                ? Optional.of(reference.substring("dep:".length()))
                : Optional.empty();
    }
}
