package com.memetrade.txsigner.codec;

import java.io.ByteArrayOutputStream;

/**
 * Solana "short vec" length (compact-u16): 7 bits per byte, at most 3 bytes, at most 0xFFFF. Like the runtime, only
 * the canonical (shortest) encoding is accepted, so one message has exactly one byte representation.
 */
public final class CompactU16 {

    public static final int MAX_VALUE = 0xFFFF;

    private CompactU16() {}

    public static int read(ByteReader reader) {
        int value = 0;
        for (int index = 0; index < 3; index++) {
            int element = reader.u8();
            if (index == 2 && element > 0x03) {
                throw new CodecException("compact-u16 overflow");
            }
            value |= (element & 0x7F) << (7 * index);
            if ((element & 0x80) == 0) {
                if (index > 0 && element == 0) {
                    throw new CodecException("compact-u16 is not canonical");
                }
                return value;
            }
        }
        throw new CodecException("compact-u16 overflow");
    }

    public static void write(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > MAX_VALUE) {
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
}
