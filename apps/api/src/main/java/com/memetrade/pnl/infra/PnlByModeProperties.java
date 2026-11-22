package com.memetrade.pnl.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 261121-paper-real-unico U1 (NFR1.9, NFR1.10, infrastructure-specification.md "Configuração nova"): the settings of
 * {@code GET /api/v1/pnl/by-mode} under {@code memetrade.pnl.by-mode}. The defaults live in {@code application.yml};
 * {@code block-timeout} and {@code real-max-orders} may also come from the environment of the api task
 * ({@code MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT}, {@code MEMETRADE_PNL_BYMODE_REALMAXORDERS}) through the relaxed binding,
 * which only works because the keys are read here and never with {@code @Value}. A value out of its domain stops the
 * process with a message naming the property.
 *
 * @param blockTimeout the time limit of each block, counted from the submission to the pool (100 ms to 900 ms)
 * @param poolSize the threads of the pool {@code pnl-mode-block} (1 to 16)
 * @param queueCapacity the queue of that pool; full, the block is UNAVAILABLE at once (0 to 64)
 * @param realMaxOrders the most settled REAL orders the U1 block reads for one person (15,000 to 200,000)
 */
@Validated
@ConfigurationProperties("memetrade.pnl.by-mode")
public record PnlByModeProperties(
        Duration blockTimeout, Integer poolSize, Integer queueCapacity, Integer realMaxOrders) {

    public static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofMillis(800);
    public static final int DEFAULT_POOL_SIZE = 8;
    public static final int DEFAULT_QUEUE_CAPACITY = 16;
    public static final int DEFAULT_REAL_MAX_ORDERS = 50_000;

    static final Duration MIN_BLOCK_TIMEOUT = Duration.ofMillis(100);
    static final Duration MAX_BLOCK_TIMEOUT = Duration.ofMillis(900);

    public PnlByModeProperties {
        blockTimeout = blockTimeout == null ? DEFAULT_BLOCK_TIMEOUT : blockTimeout;
        poolSize = poolSize == null ? DEFAULT_POOL_SIZE : poolSize;
        queueCapacity = queueCapacity == null ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
        realMaxOrders = realMaxOrders == null ? DEFAULT_REAL_MAX_ORDERS : realMaxOrders;
        require(
                blockTimeout.compareTo(MIN_BLOCK_TIMEOUT) >= 0 && blockTimeout.compareTo(MAX_BLOCK_TIMEOUT) <= 0,
                "memetrade.pnl.by-mode.block-timeout");
        require(poolSize >= 1 && poolSize <= 16, "memetrade.pnl.by-mode.pool-size");
        require(queueCapacity >= 0 && queueCapacity <= 64, "memetrade.pnl.by-mode.queue-capacity");
        require(realMaxOrders >= 15_000 && realMaxOrders <= 200_000, "memetrade.pnl.by-mode.real-max-orders");
    }

    /** The defaults of {@code application.yml}, for the tests that build the chain without Spring. */
    public static PnlByModeProperties defaults() {
        return new PnlByModeProperties(null, null, null, null);
    }

    private static void require(boolean condition, String property) {
        if (!condition) {
            throw new IllegalArgumentException(property + " is out of its domain");
        }
    }
}
