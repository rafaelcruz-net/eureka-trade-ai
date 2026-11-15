package com.memetrade.realexecution.domain.solana;

/** One account of an instruction: a 32-byte public key and its flags (defensive copies). */
public record AccountRef(byte[] key, boolean signer, boolean writable) {

    public AccountRef {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("A Solana key has 32 bytes");
        }
        key = key.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }
}
