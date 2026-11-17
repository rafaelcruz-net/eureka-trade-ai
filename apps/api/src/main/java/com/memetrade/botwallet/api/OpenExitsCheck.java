package com.memetrade.botwallet.api;

import java.util.UUID;

/**
 * 261113-trading-real-bot U7 (BR6.9, U7-NFR4.9): declared by the M1, implemented by {@code realexits}. The
 * {@code closeForAnonymization} refuses with {@code OPEN_CONDITIONALS} while the person has a conditional that is not
 * final; the anonymization never cancels anything by itself. Without the module: none.
 */
@FunctionalInterface
public interface OpenExitsCheck {

    boolean hasOpenExits(UUID tenantId, UUID userId);
}
