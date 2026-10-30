package com.memetrade.marketdata.infra.simulated;

import java.util.Arrays;

/**
 * Base58 text for SYNTHETIC mint identifiers of the simulated source. It only formats random bytes from the seeded
 * generator; it never creates keys and the result is not a real mint.
 */
final class SyntheticMintEncoder {

    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private SyntheticMintEncoder() {}

    static String encode(byte[] input) {
        byte[] copy = Arrays.copyOf(input, input.length);
        int zeros = 0;
        while (zeros < copy.length && copy[zeros] == 0) {
            zeros++;
        }
        char[] encoded = new char[copy.length * 2];
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
