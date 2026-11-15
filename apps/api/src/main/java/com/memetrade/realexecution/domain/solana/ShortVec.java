package com.memetrade.realexecution.domain.solana;

import java.io.ByteArrayOutputStream;

/** Solana "short vec" length (compact-u16), canonical encoding only: 7 bits per byte, at most 3 bytes. */
final class ShortVec {

    private ShortVec() {}

    static void write(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("compact-u16 out of range");
        }
        int remaining = value;
        while (true) {
            int element = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining == 0) {
                out.write(element);
                return;
            }
            out.write(element | 0x80);
        }
    }

    static int length(int value) {
        return value < 0x80 ? 1 : value < 0x4000 ? 2 : 3;
    }

    /** Reads a canonical compact-u16 at {@code position}; returns {value, bytes read}. */
    static int[] read(byte[] bytes, int position) {
        int value = 0;
        for (int i = 0; i < 3; i++) {
            if (position + i >= bytes.length) {
                throw new IllegalArgumentException("Unexpected end of the transaction");
            }
            int element = bytes[position + i] & 0xFF;
            if (i == 2 && element > 0x03) {
                throw new IllegalArgumentException("compact-u16 overflow");
            }
            value |= (element & 0x7F) << (7 * i);
            if ((element & 0x80) == 0) {
                if (i > 0 && element == 0) {
                    throw new IllegalArgumentException("compact-u16 is not canonical");
                }
                return new int[] {value, i + 1};
            }
        }
        throw new IllegalArgumentException("compact-u16 overflow");
    }
}
