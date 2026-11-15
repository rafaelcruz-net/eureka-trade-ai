package com.memetrade.txsigner.codec;

import java.util.Arrays;

/** Bounds-checked reader of untrusted bytes: reading past the end is a {@link CodecException}, never an index error. */
public final class ByteReader {

    private final byte[] bytes;
    private int position;

    public ByteReader(byte[] bytes) {
        this.bytes = bytes;
    }

    public int u8() {
        require(1);
        return bytes[position++] & 0xFF;
    }

    public int peekU8() {
        require(1);
        return bytes[position] & 0xFF;
    }

    public byte[] bytes(int length) {
        require(length);
        byte[] slice = Arrays.copyOfRange(bytes, position, position + length);
        position += length;
        return slice;
    }

    public int position() {
        return position;
    }

    public int remaining() {
        return bytes.length - position;
    }

    private void require(int length) {
        if (length < 0 || remaining() < length) {
            throw new CodecException("Unexpected end of input");
        }
    }
}
