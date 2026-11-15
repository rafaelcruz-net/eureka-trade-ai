package com.memetrade.realexecution.domain.solana;

import java.util.List;

/**
 * One instruction of the swap as the Jupiter swap-instructions describe it: the program, its accounts in order and its
 * data. The server builds the message from these with static keys only (U4-NFR1.5).
 */
public record SwapInstruction(byte[] programId, List<AccountRef> accounts, byte[] data) {

    public SwapInstruction {
        if (programId == null || programId.length != 32) {
            throw new IllegalArgumentException("A program id has 32 bytes");
        }
        if (data == null || data.length > 1232) {
            throw new IllegalArgumentException("Instruction data is missing or larger than a packet");
        }
        programId = programId.clone();
        accounts = List.copyOf(accounts);
        data = data.clone();
    }

    @Override
    public byte[] programId() {
        return programId.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
