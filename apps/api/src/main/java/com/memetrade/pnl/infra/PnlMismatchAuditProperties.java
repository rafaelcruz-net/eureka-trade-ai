package com.memetrade.pnl.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 261121-paper-real-unico U2 (NFR3.15, NFR3.16; infrastructure-specification.md "Configuração nova"): the settings of the
 * bounded write of the event of a reconciliation mismatch, under {@code memetrade.pnl.mismatch-audit}, with their
 * defaults in {@code application.yml} only. A value out of its domain stops the process with a message naming the
 * property, never the value. The size of the pool {@code pnl-mismatch-audit} itself is also read by the audit module
 * and checked by the {@code RuntimeSettingsGuard} of the api.
 *
 * @param poolSize the threads of the executor of the write, the same as the connections of its pool: 1 to 2, 2 by default
 * @param writeTimeout the most the REAL block waits for the write: 50 ms to 200 ms, 200 ms by default
 */
@Validated
@ConfigurationProperties("memetrade.pnl.mismatch-audit")
public record PnlMismatchAuditProperties(Integer poolSize, Duration writeTimeout) {

    public static final int DEFAULT_POOL_SIZE = 2;
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofMillis(200);

    public PnlMismatchAuditProperties {
        poolSize = poolSize == null ? DEFAULT_POOL_SIZE : poolSize;
        if (poolSize < 1 || poolSize > 2) {
            throw new IllegalArgumentException("memetrade.pnl.mismatch-audit.pool-size is out of its domain");
        }
        writeTimeout = writeTimeout == null ? DEFAULT_WRITE_TIMEOUT : writeTimeout;
        if (writeTimeout.compareTo(Duration.ofMillis(50)) < 0 || writeTimeout.compareTo(DEFAULT_WRITE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("memetrade.pnl.mismatch-audit.write-timeout is out of its domain");
        }
    }

    /** The defaults of {@code application.yml}, for the tests that build the gate without Spring. */
    public static PnlMismatchAuditProperties defaults() {
        return new PnlMismatchAuditProperties(null, null);
    }
}
