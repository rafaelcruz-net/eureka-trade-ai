package com.memetrade.shared.solana;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Minimal Base58 (Bitcoin alphabet): public keys, signatures and blockhashes only, never key material. Moved from
 * {@code botwallet.domain} by the U4 of 261113-trading-real-bot (nfr-design/logical-components.md, deviation of Q2), so
 * the bot wallet and the REAL execution use the same codec without crossing a module boundary.
 */
public final class Base58Codec {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58Codec() {}

    /** Decodes and requires exactly {@code length} bytes. */
    public static byte[] decode(String text, int length) {
        if (text == null || text.isEmpty() || text.length() > 90) {
            throw new IllegalArgumentException("Not a Base58 value");
        }
        BigInteger value = BigInteger.ZERO;
        int zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '1') {
            zeros++;
        }
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Not a Base58 value");
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        int start = magnitude.length > 0 && magnitude[0] == 0 ? 1 : 0;
        int significant = magnitude.length - start;
        if (zeros + significant != length) {
            throw new IllegalArgumentException("Expected " + length + " bytes");
        }
        byte[] out = new byte[length];
        System.arraycopy(magnitude, start, out, length - significant, significant);
        return out;
    }

    public static String encode(byte[] bytes) {
        int zeros = 0;
        while (zeros < bytes.length && bytes[zeros] == 0) {
            zeros++;
        }
        BigInteger value = new BigInteger(1, bytes);
        StringBuilder out = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] division = value.divideAndRemainder(BASE);
            out.append(ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        char[] prefix = new char[zeros];
        Arrays.fill(prefix, '1');
        return new String(prefix) + out.reverse();
    }
}
