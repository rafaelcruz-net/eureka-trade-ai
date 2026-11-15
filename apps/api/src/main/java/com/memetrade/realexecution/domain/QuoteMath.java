package com.memetrade.realexecution.domain;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;

/**
 * The integer arithmetic of a quote (BR3.2, BR4.2, BR4.3, NFR10.1): lamports and base units are {@code long}, basis
 * points are {@code int}, and every product goes through {@link BigInteger} so nothing overflows nor rounds the wrong
 * way. Never floating point.
 */
public final class QuoteMath {

    /** BR3.2: the default slippage of a person (2%) and the highest one (5%). */
    public static final int DEFAULT_SLIPPAGE_BPS = 200;

    public static final int MAX_SLIPPAGE_BPS = 500;

    /** The fixed fee of one signature in lamports. */
    public static final long BASE_FEE_PER_SIGNATURE = 5_000L;

    private static final BigInteger BPS = BigInteger.valueOf(10_000);
    private static final long MICRO_LAMPORTS_PER_LAMPORT = 1_000_000L;

    private QuoteMath() {}

    /** BR3.2: {@code floor(expectedOut x (10000 - slippage) / 10000)}. */
    public static long minOut(long expectedOut, int slippageBps) {
        if (expectedOut < 0) {
            throw new IllegalArgumentException("A quoted output is never negative");
        }
        if (slippageBps < 1 || slippageBps > MAX_SLIPPAGE_BPS) {
            throw new IllegalArgumentException("The slippage goes from 1 to 500 basis points");
        }
        return BigInteger.valueOf(expectedOut)
                .multiply(BigInteger.valueOf(10_000L - slippageBps))
                .divide(BPS)
                .longValueExact();
    }

    /** BR3.2: a quote of at most {@code maxAge} is fresh; strictly older is not. */
    public static boolean fresh(Instant quoteAt, Instant now, Duration maxAge) {
        return Duration.between(quoteAt, now).compareTo(maxAge) <= 0;
    }

    /** BR4.2: the deadline of the signing session, {@code quoteAt + window}. */
    public static Instant signingDeadline(Instant quoteAt, Duration window) {
        return quoteAt.plus(window);
    }

    /** {@code ceil(unitPrice x unitLimit / 10^6)} in lamports. */
    public static long priorityFeeLamports(long unitLimit, long microLamportsPerUnit) {
        BigInteger micro = BigInteger.valueOf(unitLimit).multiply(BigInteger.valueOf(microLamportsPerUnit));
        return micro.add(BigInteger.valueOf(MICRO_LAMPORTS_PER_LAMPORT - 1))
                .divide(BigInteger.valueOf(MICRO_LAMPORTS_PER_LAMPORT))
                .longValueExact();
    }

    /** The fee calculated before signing: base fee per signature plus the priority fee (the TxSigner checks it). */
    public static long feeLamports(int signatures, long unitLimit, long microLamportsPerUnit) {
        return Math.addExact(
                Math.multiplyExact(BASE_FEE_PER_SIGNATURE, signatures),
                priorityFeeLamports(unitLimit, microLamportsPerUnit));
    }

    /** BR3.2: the quoted input of a buy never passes its reservation. */
    public static boolean withinReservation(long quotedInputLamports, long reservedLamports) {
        return quotedInputLamports <= reservedLamports;
    }

    /** The slippage of the person: 200 when not set, never above 500. */
    public static int slippageOf(int preference) {
        if (preference <= 0) {
            return DEFAULT_SLIPPAGE_BPS;
        }
        return Math.min(preference, MAX_SLIPPAGE_BPS);
    }
}
