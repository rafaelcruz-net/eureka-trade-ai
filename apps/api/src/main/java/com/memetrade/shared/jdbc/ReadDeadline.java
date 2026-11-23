package com.memetrade.shared.jdbc;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * 261121-paper-real-unico U2 (NFR1.3, NFR1.4, NFR1.12; performance-design.md "Prazo duro dentro do fluxo"): the hard
 * deadline of a streamed read, an instant of the injected {@link Clock}, applied in two layers:
 *
 * <ol>
 *   <li><b>at every row</b> ({@link #guard}): the clock is checked before the row reaches the consumer; past the limit
 *       {@link ReadDeadlineExceeded} is thrown and no further row is processed;
 *   <li><b>at every fetch</b>: before the first execution of a statement ({@link #applyStatementTimeout}) and again at
 *       every row that is a multiple of the fetch size (before the driver asks for the next block), the time that
 *       remains is applied as {@code set local statement_timeout} on the same connection and transaction, so each
 *       block of rows is bounded by what remains, and a fetch past it ends with SQLSTATE {@code 57014}.
 * </ol>
 *
 * Never a watchdog thread nor {@code Statement.cancel()}: an out-of-band cancel could land on a later statement of the
 * caller and abort the transaction a savepoint is protecting. The time spent in Java between two fetches counts in the
 * budget through layer 1.
 */
public final class ReadDeadline {

    /** SQLSTATE of a statement cancelled by {@code statement_timeout}. */
    public static final String QUERY_CANCELED = "57014";

    private final Clock clock;
    private final Instant limit;
    private final Duration budget;

    private ReadDeadline(Clock clock, Instant limit, Duration budget) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limit = Objects.requireNonNull(limit, "limit");
        this.budget = budget;
    }

    /** A deadline {@code budget} after now on {@code clock}. */
    public static ReadDeadline after(Clock clock, Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("a read budget is positive");
        }
        return new ReadDeadline(clock, clock.instant().plus(budget), budget);
    }

    /** The earlier of this deadline and {@code budget} after now (an inner budget inside an outer one). */
    public ReadDeadline within(Duration budget) {
        ReadDeadline inner = after(clock, budget);
        return inner.limit.isBefore(limit) ? inner : this;
    }

    public Instant limit() {
        return limit;
    }

    /** The time that remains, never negative. */
    public Duration remaining() {
        Duration remaining = Duration.between(clock.instant(), limit);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** @throws ReadDeadlineExceeded when the limit is reached */
    public void check() {
        if (!clock.instant().isBefore(limit)) {
            throw new ReadDeadlineExceeded(budget);
        }
    }

    /**
     * Applies the time that remains as {@code set local statement_timeout} (milliseconds, rounded up), through
     * {@code execute} on the connection of the read.
     *
     * @throws ReadDeadlineExceeded when nothing remains (no statement is executed)
     */
    public void applyStatementTimeout(Consumer<String> execute) {
        Duration remaining = remaining();
        if (remaining.isZero()) {
            throw new ReadDeadlineExceeded(budget);
        }
        long millis = remaining.toMillis() + (remaining.toNanosPart() % 1_000_000 == 0 ? 0 : 1);
        execute.accept("set local statement_timeout = " + Math.max(1, millis));
    }

    /**
     * The two layers around {@code rows}: the clock checked at every row, and the statement timeout applied again
     * through {@code execute} after every {@code fetchSize} rows.
     */
    public RowCallbackHandler guard(RowCallbackHandler rows, int fetchSize, Consumer<String> execute) {
        if (fetchSize < 1) {
            throw new IllegalArgumentException("the fetch size is at least 1");
        }
        long[] count = {0};
        return rs -> {
            check();
            rows.processRow(rs);
            count[0]++;
            if (count[0] % fetchSize == 0) {
                applyStatementTimeout(execute);
            }
        };
    }

    /**
     * True when {@code failure} is a timeout of a read: {@link ReadDeadlineExceeded}, or a statement cancelled with
     * SQLSTATE {@value #QUERY_CANCELED} anywhere in its causes.
     */
    public static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ReadDeadlineExceeded) {
                return true;
            }
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                return false;
            }
        }
        return false;
    }
}
