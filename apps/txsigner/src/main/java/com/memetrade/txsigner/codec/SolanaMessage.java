package com.memetrade.txsigner.codec;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Own reader and writer of a Solana message, legacy or v0 (NFR1.3: no Solana SDK in production). Parsing is strict:
 * inconsistent header, out-of-range index, unknown version, truncation and trailing bytes are all refused, and a parsed
 * message serializes back to the exact same bytes.
 */
public final class SolanaMessage {

    public static final int KEY_LENGTH = 32;
    private static final int MAX_ACCOUNTS = 256;
    private static final int VERSION_PREFIX = 0x80;

    public enum Version {
        LEGACY,
        V0
    }

    private final Version version;
    private final MessageHeader header;
    private final List<byte[]> accountKeys;
    private final byte[] recentBlockhash;
    private final List<CompiledInstruction> instructions;
    private final List<AddressTableLookup> addressTableLookups;

    private SolanaMessage(
            Version version,
            MessageHeader header,
            List<byte[]> accountKeys,
            byte[] recentBlockhash,
            List<CompiledInstruction> instructions,
            List<AddressTableLookup> addressTableLookups) {
        this.version = version;
        this.header = header;
        this.accountKeys = accountKeys;
        this.recentBlockhash = recentBlockhash;
        this.instructions = instructions;
        this.addressTableLookups = addressTableLookups;
    }

    public static SolanaMessage parse(byte[] bytes) {
        ByteReader reader = new ByteReader(bytes);
        Version version = readVersion(reader);
        MessageHeader header = new MessageHeader(reader.u8(), reader.u8(), reader.u8());
        List<byte[]> keys = readKeys(reader);
        validateHeader(header, keys.size());
        byte[] blockhash = reader.bytes(KEY_LENGTH);
        List<CompiledInstruction> instructions = readInstructions(reader);
        List<AddressTableLookup> lookups =
                version == Version.V0 ? readLookups(reader) : Collections.<AddressTableLookup>emptyList();
        if (reader.remaining() != 0) {
            throw new CodecException("Trailing bytes after the message");
        }
        SolanaMessage message = new SolanaMessage(version, header, keys, blockhash, instructions, lookups);
        message.validateIndexes();
        return message;
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (version == Version.V0) {
            out.write(VERSION_PREFIX);
        }
        out.write(header.requiredSignatures());
        out.write(header.readonlySigned());
        out.write(header.readonlyUnsigned());
        CompactU16.write(out, accountKeys.size());
        accountKeys.forEach(out::writeBytes);
        out.writeBytes(recentBlockhash);
        CompactU16.write(out, instructions.size());
        for (CompiledInstruction instruction : instructions) {
            out.write(instruction.programIdIndex());
            writeIndexes(out, instruction.accountIndexes());
            CompactU16.write(out, instruction.data().length);
            out.writeBytes(instruction.data());
        }
        if (version == Version.V0) {
            CompactU16.write(out, addressTableLookups.size());
            for (AddressTableLookup lookup : addressTableLookups) {
                out.writeBytes(lookup.tableKey());
                writeIndexes(out, lookup.writableIndexes());
                writeIndexes(out, lookup.readonlyIndexes());
            }
        }
        return out.toByteArray();
    }

    public Version version() {
        return version;
    }

    public MessageHeader header() {
        return header;
    }

    /** Static account keys (copies). */
    public List<byte[]> accountKeys() {
        return accountKeys.stream().map(byte[]::clone).toList();
    }

    public byte[] accountKey(int index) {
        return accountKeys.get(index).clone();
    }

    public byte[] recentBlockhash() {
        return recentBlockhash.clone();
    }

    public List<CompiledInstruction> instructions() {
        return instructions;
    }

    public List<AddressTableLookup> addressTableLookups() {
        return addressTableLookups;
    }

    public byte[] programId(CompiledInstruction instruction) {
        return accountKey(instruction.programIdIndex());
    }

    /** Static keys plus every account loaded from the lookup tables. */
    public int totalAccountCount() {
        return accountKeys.size()
                + addressTableLookups.stream()
                        .mapToInt(AddressTableLookup::accountCount)
                        .sum();
    }

    public boolean isSigner(int index) {
        return index >= 0 && index < header.requiredSignatures();
    }

    /** Writable flag of a STATIC key, from the header. */
    public boolean isWritable(int index) {
        if (index < header.requiredSignatures()) {
            return index < header.requiredSignatures() - header.readonlySigned();
        }
        return index < accountKeys.size() - header.readonlyUnsigned();
    }

    private static Version readVersion(ByteReader reader) {
        int first = reader.peekU8();
        if ((first & VERSION_PREFIX) == 0) {
            return Version.LEGACY;
        }
        reader.u8();
        if ((first & 0x7F) != 0) {
            throw new CodecException("Unsupported message version " + (first & 0x7F));
        }
        return Version.V0;
    }

    private static List<byte[]> readKeys(ByteReader reader) {
        int count = CompactU16.read(reader);
        if (count == 0 || count > MAX_ACCOUNTS) {
            throw new CodecException("Invalid number of account keys");
        }
        List<byte[]> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(reader.bytes(KEY_LENGTH));
        }
        return Collections.unmodifiableList(keys);
    }

    private static void validateHeader(MessageHeader header, int keyCount) {
        int required = header.requiredSignatures();
        if (required < 1 || required > keyCount) {
            throw new CodecException("Invalid number of required signatures");
        }
        if (header.readonlySigned() >= required) {
            throw new CodecException("The fee payer must be a writable signer");
        }
        if (header.readonlyUnsigned() > keyCount - required) {
            throw new CodecException("Invalid number of readonly unsigned accounts");
        }
    }

    private static List<CompiledInstruction> readInstructions(ByteReader reader) {
        int count = CompactU16.read(reader);
        List<CompiledInstruction> instructions = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            int programIndex = reader.u8();
            int[] accounts = readIndexes(reader);
            byte[] data = reader.bytes(CompactU16.read(reader));
            instructions.add(new CompiledInstruction(programIndex, accounts, data));
        }
        return Collections.unmodifiableList(instructions);
    }

    private static List<AddressTableLookup> readLookups(ByteReader reader) {
        int count = CompactU16.read(reader);
        List<AddressTableLookup> lookups = new ArrayList<>(Math.min(count, 16));
        for (int i = 0; i < count; i++) {
            byte[] table = reader.bytes(KEY_LENGTH);
            lookups.add(new AddressTableLookup(table, readIndexes(reader), readIndexes(reader)));
        }
        return Collections.unmodifiableList(lookups);
    }

    private static int[] readIndexes(ByteReader reader) {
        int count = CompactU16.read(reader);
        if (count > reader.remaining()) {
            throw new CodecException("Unexpected end of input");
        }
        int[] indexes = new int[count];
        for (int i = 0; i < count; i++) {
            indexes[i] = reader.u8();
        }
        return indexes;
    }

    private static void writeIndexes(ByteArrayOutputStream out, int[] indexes) {
        CompactU16.write(out, indexes.length);
        for (int index : indexes) {
            out.write(index);
        }
    }

    private void validateIndexes() {
        int total = totalAccountCount();
        if (total > MAX_ACCOUNTS) {
            throw new CodecException("More than 256 accounts");
        }
        for (CompiledInstruction instruction : instructions) {
            if (instruction.programIdIndex() >= accountKeys.size()) {
                throw new CodecException("Program index outside the static keys");
            }
            for (int index : instruction.accountIndexes()) {
                if (index >= total) {
                    throw new CodecException("Account index outside the account list");
                }
            }
        }
    }
}
