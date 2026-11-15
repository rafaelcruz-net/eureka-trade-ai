package com.memetrade.realexecution.domain.solana;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiled account list of a swap message (U4-NFR1.5): the fee payer first, then every key in the order of the
 * Solana runtime and of the SDK: writable signers, readonly signers, writable accounts, readonly accounts, each group
 * in first-appearance order (the accounts of an instruction, then its program). A key used twice keeps the strongest
 * flags. The plan knows the size of the message and of the transaction BEFORE anything is signed, so a route that does
 * not fit one packet is refused early. Static keys only: there is never an address lookup table.
 */
public final class MessagePlan {

    private static final int KEY = 32;

    private final List<Key> keys;
    private final List<SwapInstruction> instructions;
    private final Map<ByteBuffer, Integer> index;
    private final int requiredSignatures;
    private final int readonlySigned;
    private final int readonlyUnsigned;

    private MessagePlan(List<Key> keys, List<SwapInstruction> instructions) {
        this.keys = keys;
        this.instructions = instructions;
        this.index = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            index.put(ByteBuffer.wrap(keys.get(i).key), i);
        }
        this.requiredSignatures = (int) keys.stream().filter(k -> k.signer).count();
        this.readonlySigned =
                (int) keys.stream().filter(k -> k.signer && !k.writable).count();
        this.readonlyUnsigned =
                (int) keys.stream().filter(k -> !k.signer && !k.writable).count();
    }

    /** Compiles the keys of {@code instructions} with {@code feePayer} as the first writable signer. */
    public static MessagePlan of(byte[] feePayer, List<SwapInstruction> instructions) {
        if (feePayer == null || feePayer.length != KEY) {
            throw new IllegalArgumentException("The fee payer is a 32-byte key");
        }
        if (instructions == null || instructions.isEmpty()) {
            throw new IllegalArgumentException("A swap message has at least one instruction");
        }
        Map<ByteBuffer, Key> merged = new LinkedHashMap<>();
        add(merged, feePayer, true, true);
        for (SwapInstruction instruction : instructions) {
            for (AccountRef account : instruction.accounts()) {
                add(merged, account.key(), account.signer(), account.writable());
            }
            add(merged, instruction.programId(), false, false);
        }
        List<Key> ordered = new ArrayList<>(merged.values());
        // Stable sort: signers first, then writable first; the payer stays first (it was inserted first).
        ordered.sort((a, b) -> {
            if (a.signer != b.signer) {
                return a.signer ? -1 : 1;
            }
            if (a.writable != b.writable) {
                return a.writable ? -1 : 1;
            }
            return 0;
        });
        if (ordered.size() > 256) {
            throw new IllegalArgumentException("More than 256 accounts");
        }
        return new MessagePlan(Collections.unmodifiableList(ordered), List.copyOf(instructions));
    }

    private static void add(Map<ByteBuffer, Key> merged, byte[] key, boolean signer, boolean writable) {
        merged.merge(
                ByteBuffer.wrap(key.clone()),
                new Key(key.clone(), signer, writable),
                (old, added) -> new Key(old.key, old.signer || added.signer, old.writable || added.writable));
    }

    /** The static keys in message order (copies). */
    public List<byte[]> keys() {
        return keys.stream().map(k -> k.key.clone()).toList();
    }

    public int requiredSignatures() {
        return requiredSignatures;
    }

    public int readonlySigned() {
        return readonlySigned;
    }

    public int readonlyUnsigned() {
        return readonlyUnsigned;
    }

    /** The index of {@code key} among the static keys, or -1. */
    public int indexOf(byte[] key) {
        return index.getOrDefault(ByteBuffer.wrap(key), -1);
    }

    /** Size of the v0 message in bytes (prefix, header, keys, blockhash, instructions, empty table list). */
    public int messageSize() {
        return compileV0(new byte[KEY]).length;
    }

    /** Size of the wire transaction: compact count, one 64-byte slot per signer, the message. */
    public int transactionSize() {
        return ShortVec.length(requiredSignatures) + 64 * requiredSignatures + messageSize();
    }

    public boolean fitsOnePacket() {
        return transactionSize() <= MessageBuilder.MAX_TRANSACTION_SIZE;
    }

    /** The v0 message with {@code blockhash}: prefix 0x80, header, keys, blockhash, instructions, no lookup table. */
    public byte[] compileV0(byte[] blockhash) {
        if (blockhash == null || blockhash.length != KEY) {
            throw new IllegalArgumentException("A blockhash has 32 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80);
        out.write(requiredSignatures);
        out.write(readonlySigned);
        out.write(readonlyUnsigned);
        ShortVec.write(out, keys.size());
        keys.forEach(k -> out.writeBytes(k.key));
        out.writeBytes(blockhash);
        ShortVec.write(out, instructions.size());
        for (SwapInstruction instruction : instructions) {
            out.write(indexOf(instruction.programId()));
            List<AccountRef> accounts = instruction.accounts();
            ShortVec.write(out, accounts.size());
            accounts.forEach(account -> out.write(indexOf(account.key())));
            byte[] data = instruction.data();
            ShortVec.write(out, data.length);
            out.writeBytes(data);
        }
        ShortVec.write(out, 0);
        return out.toByteArray();
    }

    private record Key(byte[] key, boolean signer, boolean writable) {}
}
