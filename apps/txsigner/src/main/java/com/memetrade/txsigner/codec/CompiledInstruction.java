package com.memetrade.txsigner.codec;

/**
 * One instruction of a message: the index of its program among the static keys, the indexes of its accounts (static
 * keys first, then the lookup table entries) and its data. Arrays are defensive copies.
 */
public record CompiledInstruction(int programIdIndex, int[] accountIndexes, byte[] data) {

    public CompiledInstruction {
        accountIndexes = accountIndexes.clone();
        data = data.clone();
    }

    @Override
    public int[] accountIndexes() {
        return accountIndexes.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
