package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.anonymization.AnonymizationTimeouts;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * NFR5.4 of u5-anonymization: {@code set local lock_timeout} with the value of the bots commands
 * ({@code memetrade.bots.lock-timeout}, 2 s; no new property), valid until the end of the current transaction only.
 */
@Component
public class JdbcAnonymizationTimeouts implements AnonymizationTimeouts {

    private final JdbcClient jdbc;
    private final Duration lockTimeout;

    public JdbcAnonymizationTimeouts(JdbcClient jdbc, @Value("${memetrade.bots.lock-timeout}") Duration lockTimeout) {
        if (lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("memetrade.bots.lock-timeout must be positive");
        }
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout;
    }

    @Override
    public void applyLockTimeout() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The lock timeout of the anonymization needs an open transaction");
        }
        jdbc.sql("set local lock_timeout = '" + lockTimeout.toMillis() + "ms'").update();
    }
}
