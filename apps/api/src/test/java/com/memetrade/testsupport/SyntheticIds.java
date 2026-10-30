package com.memetrade.testsupport;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Synthetic Base58 identifiers for fixtures. They are random 32-byte values generated per run and never real
 * wallets or mints (team.md: fixtures never use real wallets, mints or personal data).
 */
public final class SyntheticIds {

    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SyntheticIds() {}

    public static String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        bytes[0] = (byte) (bytes[0] | 0x01); // avoid leading zero bytes so the length stays within 32..44
        return encode(bytes);
    }

    public static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        byte[] copy = Arrays.copyOf(input, input.length);
        char[] encoded = new char[input.length * 2];
        int outputStart = encoded.length;
        int inputStart = zeros;
        while (inputStart < copy.length) {
            int remainder = 0;
            for (int i = inputStart; i < copy.length; i++) {
                int digit = (copy[i] & 0xFF) + remainder * 256;
                copy[i] = (byte) (digit / 58);
                remainder = digit % 58;
            }
            encoded[--outputStart] = ALPHABET[remainder];
            if (copy[inputStart] == 0) {
                inputStart++;
            }
        }
        while (outputStart < encoded.length && encoded[outputStart] == ALPHABET[0]) {
            outputStart++;
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = ALPHABET[0];
        }
        return new String(encoded, outputStart, encoded.length - outputStart);
    }
}
