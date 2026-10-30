package com.memetrade.identity.domain.signature;

import java.util.Arrays;

/**
 * Own Base58 decoder with the Bitcoin alphabet (security-design.md, NFR6.3). Decoding only: production code never
 * creates keys or signatures. Characters outside the alphabet and unexpected lengths are refused.
 */
public final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            INDEXES[ALPHABET.charAt(i)] = i;
        }
    }

    private Base58() {}

    /** Decodes a Base58 string; leading '1' characters become leading zero bytes. */
    public static byte[] decode(String input) {
        if (input == null) {
            throw new InvalidEncodingException("Base58 input is missing");
        }
        if (input.isEmpty()) {
            return new byte[0];
        }
        byte[] digits = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = c < 128 ? INDEXES[c] : -1;
            if (digit < 0) {
                throw new InvalidEncodingException("Character outside the Base58 alphabet at position " + i);
            }
            digits[i] = (byte) digit;
        }
        int zeros = 0;
        while (zeros < digits.length && digits[zeros] == 0) {
            zeros++;
        }
        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        int inputStart = zeros;
        while (inputStart < digits.length) {
            int remainder = 0;
            for (int i = inputStart; i < digits.length; i++) {
                int value = (digits[i] & 0xFF) + remainder * 58;
                digits[i] = (byte) (value / 256);
                remainder = value % 256;
            }
            decoded[--outputStart] = (byte) remainder;
            if (digits[inputStart] == 0) {
                inputStart++;
            }
        }
        while (outputStart < decoded.length && decoded[outputStart] == 0) {
            outputStart++;
        }
        return Arrays.copyOfRange(decoded, outputStart - zeros, decoded.length);
    }

    /** Decodes and requires exactly {@code expectedLength} bytes (32 for a public key, 64 for a signature). */
    public static byte[] decodeExact(String input, int expectedLength) {
        byte[] decoded = decode(input);
        if (decoded.length != expectedLength) {
            throw new InvalidEncodingException("Expected " + expectedLength + " bytes but decoded " + decoded.length);
        }
        return decoded;
    }

    /** Refused Base58 input (never echoes the input itself). */
    public static final class InvalidEncodingException extends RuntimeException {
        public InvalidEncodingException(String message) {
            super(message);
        }
    }
}
