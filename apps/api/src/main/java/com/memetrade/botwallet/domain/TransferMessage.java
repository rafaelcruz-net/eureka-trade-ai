package com.memetrade.botwallet.domain;

import com.memetrade.shared.solana.Base58Codec;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Builds the unsigned withdrawal in the exact shape of BR5.1 (legacy message, no address table, no durable nonce): keys
 * [bot wallet (signer, fee payer), main wallet, System Program, Compute Budget], optional SetComputeUnitLimit and
 * SetComputeUnitPrice, then one System transfer of the amount from the bot wallet to the main wallet. The wire form has
 * one empty signature slot, which only the TxSigner fills.
 */
public final class TransferMessage {

    private static final byte[] SYSTEM_PROGRAM = new byte[32];
    private static final byte[] COMPUTE_BUDGET = Base58Codec.decode("ComputeBudget111111111111111111111111111111", 32);

    private TransferMessage() {}

    public static String unsignedBase64(
            String botWallet, String mainWallet, long lamports, int unitLimit, long unitPrice, String blockhash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.writeBytes(new byte[64]);
        out.writeBytes(message(botWallet, mainWallet, lamports, unitLimit, unitPrice, blockhash));
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static byte[] message(
            String botWallet, String mainWallet, long lamports, int unitLimit, long unitPrice, String blockhash) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("A withdrawal moves a positive amount");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 0, 2});
        out.write(4);
        out.writeBytes(Base58Codec.decode(botWallet, 32));
        out.writeBytes(Base58Codec.decode(mainWallet, 32));
        out.writeBytes(SYSTEM_PROGRAM);
        out.writeBytes(COMPUTE_BUDGET);
        out.writeBytes(Base58Codec.decode(blockhash, 32));
        out.write(unitPrice > 0 ? 3 : 2);
        instruction(out, 3, new int[0], concat(new byte[] {2}, le(unitLimit, 4)));
        if (unitPrice > 0) {
            instruction(out, 3, new int[0], concat(new byte[] {3}, le(unitPrice, 8)));
        }
        instruction(out, 2, new int[] {0, 1}, concat(le(2, 4), le(lamports, 8)));
        return out.toByteArray();
    }

    private static void instruction(ByteArrayOutputStream out, int program, int[] accounts, byte[] data) {
        out.write(program);
        out.write(accounts.length);
        for (int account : accounts) {
            out.write(account);
        }
        out.write(data.length);
        out.writeBytes(data);
    }

    private static byte[] le(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (value >>> (8 * i));
        }
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
