package com.memetrade.botwallet.api;

/**
 * 261113-trading-real-bot U7 (BR8.1, BR9.3, U7-NFR4.10): declared by the M1, implemented by {@code realexits} from
 * {@code memetrade.real.exits.enabled} (off by default in every profile). Off: raising the mode to AUTO_EXITS is refused
 * with {@code EXITS_DISABLED} and {@code GET /api/v1/real/release} answers {@code exitsEnabled: false}; whoever is
 * already in AUTO_EXITS stays there. Without the module: off.
 */
@FunctionalInterface
public interface ExitsAvailability {

    boolean exitsEnabled();
}
