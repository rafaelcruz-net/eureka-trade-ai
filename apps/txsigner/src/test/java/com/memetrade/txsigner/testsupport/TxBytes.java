package com.memetrade.txsigner.testsupport;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes Solana messages byte by byte in TEST code, independently of the production codec, so the codec and the
 * policy are checked against bytes they did not produce. Supports legacy and v0 messages (with address tables).
 */
public final class TxBytes {

    public static final byte[] SYSTEM_PROGRAM = new byte[32];
    public static final byte[] COMPUTE_BUDGET_PROGRAM =
            com.memetrade.txsigner.codec.Base58.decode("ComputeBudget111111111111111111111111111111");

    private int requiredSignatures = 1;
    private int readonlySigned;
    private int readonlyUnsigned;
    private final List<byte[]> keys = new ArrayList<>();
    private byte[] blockhash = filled(7);
    private final List<byte[]> instructions = new ArrayList<>();
    private boolean v0;
    private final List<byte[]> addressTables = new ArrayList<>();

    public static TxBytes message() {
        return new TxBytes();
    }

    /**
     * The exact withdrawal shape of BR5.1: keys [bot, main, system program, compute budget], optional compute unit
     * limit and price, one System Program transfer from the bot to the main wallet.
     */
    public static TxBytes withdrawal(byte[] bot, byte[] main, long lamports, Integer unitLimit, Long microLamports) {
        TxBytes tx =
                message().header(1, 0, 2).key(bot).key(main).key(SYSTEM_PROGRAM).key(COMPUTE_BUDGET_PROGRAM);
        if (unitLimit != null) {
            tx.instruction(3, new int[0], setComputeUnitLimit(unitLimit));
        }
        if (microLamports != null) {
            tx.instruction(3, new int[0], setComputeUnitPrice(microLamports));
        }
        return tx.instruction(2, new int[] {0, 1}, transfer(lamports));
    }

    public TxBytes header(int signatures, int readonlySignedCount, int readonlyUnsignedCount) {
        this.requiredSignatures = signatures;
        this.readonlySigned = readonlySignedCount;
        this.readonlyUnsigned = readonlyUnsignedCount;
        return this;
    }

    public TxBytes key(byte[] key) {
        keys.add(key.clone());
        return this;
    }

    public TxBytes blockhash(byte[] hash) {
        this.blockhash = hash.clone();
        return this;
    }

    public TxBytes instruction(int programIndex, int[] accountIndexes, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(programIndex);
        compact(out, accountIndexes.length);
        for (int index : accountIndexes) {
            out.write(index);
        }
        compact(out, data.length);
        out.writeBytes(data);
        instructions.add(out.toByteArray());
        return this;
    }

    public TxBytes versioned() {
        this.v0 = true;
        return this;
    }

    public TxBytes addressTable(byte[] tableKey, int[] writable, int[] readonly) {
        this.v0 = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(tableKey);
        compact(out, writable.length);
        for (int index : writable) {
            out.write(index);
        }
        compact(out, readonly.length);
        for (int index : readonly) {
            out.write(index);
        }
        addressTables.add(out.toByteArray());
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (v0) {
            out.write(0x80);
        }
        out.write(requiredSignatures);
        out.write(readonlySigned);
        out.write(readonlyUnsigned);
        compact(out, keys.size());
        keys.forEach(out::writeBytes);
        out.writeBytes(blockhash);
        compact(out, instructions.size());
        instructions.forEach(out::writeBytes);
        if (v0) {
            compact(out, addressTables.size());
            addressTables.forEach(out::writeBytes);
        }
        return out.toByteArray();
    }

    /** Wire transaction: compact count of signatures, the signatures, then the message. */
    public static byte[] transaction(byte[] message, byte[]... signatures) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        compact(out, signatures.length);
        for (byte[] signature : signatures) {
            out.writeBytes(signature);
        }
        out.writeBytes(message);
        return out.toByteArray();
    }

    /** Unsigned wire transaction with {@code count} zeroed signature slots, as the worker sends it to the TxSigner. */
    public static byte[] unsigned(byte[] message, int count) {
        byte[][] empty = new byte[count][];
        Arrays.setAll(empty, i -> new byte[64]);
        return transaction(message, empty);
    }

    public static byte[] transfer(long lamports) {
        return concat(u32(2), u64(lamports));
    }

    public static byte[] systemInstruction(int index, byte[] rest) {
        return concat(u32(index), rest);
    }

    public static byte[] setComputeUnitLimit(int units) {
        return concat(new byte[] {2}, u32(units));
    }

    public static byte[] setComputeUnitPrice(long microLamports) {
        return concat(new byte[] {3}, u64(microLamports));
    }

    public static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    public static byte[] u32(long value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    public static byte[] u64(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >>> (8 * i));
        }
        return bytes;
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static void compact(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while (true) {
            int element = remaining & 0x7f;
            remaining >>>= 7;
            if (remaining == 0) {
                out.write(element);
                return;
            }
            out.write(element | 0x80);
        }
    }
}
