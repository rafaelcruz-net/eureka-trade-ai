package com.memetrade.txsigner.codec;

/** One address lookup table of a v0 message (ALT): the table account and the entries it loads. */
public record AddressTableLookup(byte[] tableKey, int[] writableIndexes, int[] readonlyIndexes) {

    public AddressTableLookup {
        tableKey = tableKey.clone();
        writableIndexes = writableIndexes.clone();
        readonlyIndexes = readonlyIndexes.clone();
    }

    @Override
    public byte[] tableKey() {
        return tableKey.clone();
    }

    @Override
    public int[] writableIndexes() {
        return writableIndexes.clone();
    }

    @Override
    public int[] readonlyIndexes() {
        return readonlyIndexes.clone();
    }

    public int accountCount() {
        return writableIndexes.length + readonlyIndexes.length;
    }
}
