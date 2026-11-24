package com.memetrade.pnl.web;

import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.modeview.ModePnlStatement;
import com.memetrade.shared.web.QueryParameters;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 261121-paper-real-unico U3 (BR2.4, AC7.2.3, NFR4.7; contract C4 and C6): the period of a request of the P&L by mode or
 * of the comparison, read from {@code period}, {@code from} and {@code to} WITHOUT any read of the database.
 *
 * <ul>
 *   <li>{@code period} absent or {@code ALL}: the window ALL; {@code from} or {@code to} with it is refused;
 *   <li>{@code RANGE}: {@code from} and {@code to} required, ISO 8601 instants, {@code from} before {@code to}, at most
 *       366 days in UTC ({@code [from, to)});
 *   <li>any other {@code period} is refused.
 * </ul>
 *
 * Every refusal is a violation of the field it is about, thrown as {@code 400 VALIDATION_FAILED} by
 * {@link QueryParameters#validate()}.
 */
final class PnlWindows {

    private PnlWindows() {}

    /**
     * The window of the request, or empty with the violations recorded in {@code query} (the caller validates).
     *
     * @param query the parameters of the route, already opened with its closed list
     */
    static Optional<PnlWindow> read(QueryParameters query) {
        String period = query.text("period").orElse(ModePnlStatement.ALL);
        boolean from = query.text("from").isPresent();
        boolean to = query.text("to").isPresent();
        if (ModePnlStatement.ALL.equals(period)) {
            if (from) {
                query.violation("from", "Only with the period RANGE.");
            }
            if (to) {
                query.violation("to", "Only with the period RANGE.");
            }
            return from || to ? Optional.empty() : Optional.of(PnlWindow.all());
        }
        if (!ModePnlStatement.RANGE.equals(period)) {
            query.violation("period", "Must be ALL or RANGE.");
            return Optional.empty();
        }
        Optional<Instant> start = query.requiredInstant("from");
        Optional<Instant> end = query.requiredInstant("to");
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        if (!start.get().isBefore(end.get())) {
            query.violation("from", "from must be before to.");
            return Optional.empty();
        }
        if (Duration.between(start.get(), end.get()).compareTo(PnlWindow.Range.MAX) > 0) {
            query.violation("to", "The period has at most 366 days.");
            return Optional.empty();
        }
        return Optional.of(new PnlWindow.Range(start.get(), end.get()));
    }

    /** The period of the answer: {@code ALL}, or {@code RANGE} with its two instants. */
    static ModePnlDtos.PnlPeriodDto dto(PnlWindow window) {
        return switch (window) {
            case PnlWindow.All all -> new ModePnlDtos.PnlPeriodDto(ModePnlStatement.ALL, null, null);
            case PnlWindow.Range range ->
                new ModePnlDtos.PnlPeriodDto(ModePnlStatement.RANGE, range.from(), range.to());
        };
    }
}
