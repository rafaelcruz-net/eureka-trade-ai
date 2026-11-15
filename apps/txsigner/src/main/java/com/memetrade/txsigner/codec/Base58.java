package com.memetrade.txsigner.codec;

import java.util.Arrays;

/**
 * Own Base58 with the Bitcoin alphabet (project.md: no Solana SDK in production). The input length is checked before
 * any decoding, so an oversized value never reaches the quadratic loop (security-design.md "Validação de entrada").
 */
public final class Base58 {

    /** Longest accepted input: a transaction signature (64 bytes) is at most 88 characters. */
    public static final int MAX_INPUT_LENGTH = 128;

    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEXES[ALPHABET[i]] = i;
        }
    }

    private Base58() {}

    public static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        byte[] digits = Arrays.copyOf(input, input.length);
        char[] encoded = new char[input.length * 2];
        int outputStart = encoded.length;
        int inputStart = zeros;
        while (inputStart < digits.length) {
            int remainder = 0;
            for (int i = inputStart; i < digits.length; i++) {
                int value = (digits[i] & 0xFF) + remainder * 256;
                digits[i] = (byte) (value / 58);
                remainder = value % 58;
            }
            encoded[--outputStart] = ALPHABET[remainder];
            if (digits[inputStart] == 0) {
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

    public static byte[] decode(String input) {
        if (input == null) {
            throw new CodecException("Base58 input is missing");
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            throw new CodecException("Base58 input longer than " + MAX_INPUT_LENGTH + " characters");
        }
        if (input.isEmpty()) {
            return new byte[0];
        }
        byte[] digits = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = c < 128 ? INDEXES[c] : -1;
            if (digit < 0) {
                throw new CodecException("Character outside the Base58 alphabet at position " + i);
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

    /** Decodes and requires exactly {@code expectedLength} bytes (32 for an address, 64 for a signature). */
    public static byte[] decodeExact(String input, int expectedLength) {
        byte[] decoded = decode(input);
        if (decoded.length != expectedLength) {
            throw new CodecException("Expected " + expectedLength + " bytes but decoded " + decoded.length);
        }
        return decoded;
    }
}
