package com.memetrade.shared.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 261113-trading-real-bot U4 (infrastructure-specification.md "Prova do swap no local"): the swap recorded ONCE by
 * {@code infra/solana-fixtures/record.sh}, as the REAL Compose passes it from {@code recorded.env}. EXISTS ONLY IN THE
 * TEST PROFILE and only when {@code RECORDED_MINT} is set ({@link RecordedSwapFixtureConfig}); no other profile ever
 * creates it, so production never sees a recorded mint, a pinned simulated token nor the test strategy.
 *
 * <p>Three test-only uses, all keyed by this one bean:
 *
 * <ul>
 *   <li>the simulated source {@code sim-a} also publishes the recorded mint and the wrapped SOL with fresh values, so
 *       the scanner assesses the mint and the REAL cycle knows the price of SOL;
 *   <li>the REAL barrier treats the active source as REAL for orders of the recorded mint only (BR1.2 would otherwise
 *       refuse every REAL order while {@code sim-a} is active, as it must everywhere else);
 *   <li>the test strategy of the worker buys the recorded mint ONCE for exactly {@link #buyLamports()}.
 * </ul>
 *
 * @param mint the recorded mint ({@code RECORDED_MINT})
 * @param decimals the places of the mint, read from its recorded account ({@code RECORDED_DECIMALS})
 * @param buyLamports the input of the recorded buy ({@code RECORDED_BUY_LAMPORTS}); the recorded quote is for exactly
 *     this amount, so the bot must ask exactly this amount
 * @param expectedOutUnits the output of the recorded buy quote, in base units of the mint ({@code RECORDED_SELL_UNITS})
 */
public record RecordedSwapFixture(String mint, int decimals, long buyLamports, long expectedOutUnits) {

    /** The wrapped SOL mint: the input of the buy and the price that turns dollars into lamports. */
    public static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";

    /** The places of the wrapped SOL mint. */
    public static final int WRAPPED_SOL_DECIMALS = 9;

    /**
     * The dollar price of SOL the simulated source publishes: the probe quote recorded with the fixtures (1,000,000
     * lamports for 72,558 USDC base units). Exact decimal, never floating point.
     */
    public static final BigDecimal SOL_USD = new BigDecimal("72.558");

    /** The largest recorded buy accepted: 1 SOL, the cap the bot wallet spec activates. */
    public static final long MAX_BUY_LAMPORTS = 1_000_000_000L;

    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);
    private static final int PRICE_SCALE = 12;

    public RecordedSwapFixture {
        if (mint == null || !BASE58_ADDRESS.matcher(mint).matches() || WRAPPED_SOL.equals(mint)) {
            throw invalid("RECORDED_MINT", "a Base58 mint other than the wrapped SOL");
        }
        if (decimals < 0 || decimals > 18) {
            throw invalid("RECORDED_DECIMALS", "between 0 and 18");
        }
        if (buyLamports < 1 || buyLamports > MAX_BUY_LAMPORTS) {
            throw invalid("RECORDED_BUY_LAMPORTS", "between 1 and " + MAX_BUY_LAMPORTS);
        }
        if (expectedOutUnits < 1) {
            throw invalid("RECORDED_SELL_UNITS", "above zero");
        }
    }

    /** True for the recorded mint only (the wrapped SOL is never an order of the recorded swap). */
    public boolean isRecordedMint(String candidate) {
        return mint.equals(candidate);
    }

    /** The whole tokens of the recorded buy: {@code expectedOutUnits / 10^decimals}. */
    public BigDecimal expectedOutTokens() {
        return BigDecimal.valueOf(expectedOutUnits).movePointLeft(decimals);
    }

    /**
     * The dollar price of one whole token of the mint that matches the recorded quote at {@link #SOL_USD}:
     * {@code buyLamports / 10^9 x SOL_USD / expectedOutTokens}, at 12 places, rounded down.
     */
    public BigDecimal tokenUsd() {
        return BigDecimal.valueOf(buyLamports)
                .multiply(SOL_USD)
                .divide(LAMPORTS_PER_SOL, PRICE_SCALE + 9, RoundingMode.DOWN)
                .divide(expectedOutTokens(), PRICE_SCALE, RoundingMode.DOWN);
    }

    private static IllegalArgumentException invalid(String variable, String expected) {
        return new IllegalArgumentException(variable + " is out of range: expected " + expected);
    }
}
