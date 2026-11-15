package com.memetrade.riskcontrol.api;

import java.util.Locale;
import java.util.Objects;

/**
 * 261113-trading-real-bot U3 (U3-NFR4.1, U3-NFR4.8): the barrier failed closed. A technical failure while reading one
 * of its dependencies, or the wallet lock not obtained within 1 s, NEVER approves an order: the bot decision waits for
 * the next cycle, and the liquidation of the person answers 503. The message names the dependency only, never a value.
 * After a lock timeout the transaction of the caller is still usable (the lock is a try, never a statement error); after
 * a SQL failure it is not, and {@link #transactionUsable()} says so.
 */
public class RealGateUnavailable extends RuntimeException {

    /** The dependency that failed; its lower-case name is the label of {@code real_risk_errors_total}. */
    public enum Dependency {
        WALLET,
        LEDGER,
        MARKET,
        ASSESSMENT,
        LOCK;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Dependency dependency;
    private final boolean transactionUsable;

    public RealGateUnavailable(Dependency dependency, Throwable cause) {
        super("The REAL risk barrier is unavailable: dependency=" + dependency.label(), cause);
        this.dependency = Objects.requireNonNull(dependency, "dependency");
        this.transactionUsable = !hasDatabaseError(cause);
    }

    public RealGateUnavailable(Dependency dependency) {
        this(dependency, null);
    }

    public Dependency dependency() {
        return dependency;
    }

    /**
     * Review R-02 of the U3: whether the transaction of the caller can still run statements. {@code true} for the lock
     * timeout (a try never raises a database error) and for a failure without a database error; {@code false} when a
     * statement failed in PostgreSQL ({@link java.sql.SQLException} or Spring {@code DataAccessException} in the
     * causes): the database already aborted the transaction, and the caller (U4) MUST roll its cycle back, since every
     * next statement fails. The barrier then also marks the transaction rollback-only.
     */
    public boolean transactionUsable() {
        return transactionUsable;
    }

    private static boolean hasDatabaseError(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException
                    || t.getClass().getName().startsWith("org.springframework.dao.")
                    || t.getClass().getName().startsWith("org.springframework.jdbc.")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
