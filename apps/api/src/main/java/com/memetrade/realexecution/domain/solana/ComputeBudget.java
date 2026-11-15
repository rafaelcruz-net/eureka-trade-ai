package com.memetrade.realexecution.domain.solana;

import com.memetrade.realexecution.domain.QuoteMath;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the compute budget of the swap instructions (BR4.3, NFR4.3): the unit limit and the unit price, so the fee is
 * known BEFORE signing (the TxSigner checks the same number). Without a limit the runtime gives 200,000 units per other
 * instruction, at most 1.4 million.
 */
public final class ComputeBudget {

    public static final byte[] PROGRAM =
            com.memetrade.shared.solana.Base58Codec.decode("ComputeBudget111111111111111111111111111111", 32);

    private static final long DEFAULT_UNITS_PER_INSTRUCTION = 200_000L;
    private static final long MAX_UNITS = 1_400_000L;

    private ComputeBudget() {}

    /** The fee of a message with {@code signatures} signatures and these instructions. */
    public static long feeLamports(List<SwapInstruction> instructions, int signatures) {
        Long limit = null;
        long price = 0;
        int others = 0;
        for (SwapInstruction instruction : instructions) {
            if (!Arrays.equals(instruction.programId(), PROGRAM)) {
                others++;
                continue;
            }
            byte[] data = instruction.data();
            ByteBuffer value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length == 5 && data[0] == 2) {
                limit = Integer.toUnsignedLong(value.getInt(1));
            } else if (data.length == 9 && data[0] == 3) {
                price = value.getLong(1);
            }
        }
        long units = limit != null ? limit : Math.min(MAX_UNITS, DEFAULT_UNITS_PER_INSTRUCTION * others);
        return QuoteMath.feeLamports(signatures, units, price);
    }

    /** The priority part of the fee alone (checked against the ceiling before asking a signature). */
    public static long priorityFeeLamports(List<SwapInstruction> instructions, int signatures) {
        return feeLamports(instructions, signatures) - QuoteMath.BASE_FEE_PER_SIGNATURE * signatures;
    }
}
