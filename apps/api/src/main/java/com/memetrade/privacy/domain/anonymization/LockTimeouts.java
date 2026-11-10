package com.memetrade.privacy.domain.anonymization;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;

/**
 * NFR5.4: whether a failure is a lock not obtained within {@code lock_timeout} (SQLSTATE 55P03), whatever layer
 * translated it (the bots lock order, the Spring translator, or a plain SQL exception in the chain).
 */
final class LockTimeouts {

    static final String LOCK_NOT_AVAILABLE = "55P03";

    private LockTimeouts() {}

    static boolean isLockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotAcquireLockException) {
                return true;
            }
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }
}
