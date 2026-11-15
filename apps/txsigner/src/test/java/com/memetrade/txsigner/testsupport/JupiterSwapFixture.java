package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.codec.Base58;

/**
 * Fixture of proof 2 (docs/proofs/jupiter-tx.md): a v0 swap transaction with the SHAPE of what the Jupiter swap API
 * returns for a bot wallet: compute unit limit and price, an idempotent associated token account creation, the route
 * instruction of the aggregator program, and two address lookup tables. Every account other than the public program
 * ids is synthetic (team.md: fixtures never use a real wallet nor a real mint), so the bytes are fixed and recorded
 * here instead of fetched from the network.
 */
public final class JupiterSwapFixture {

    public static final byte[] JUPITER_PROGRAM = Base58.decode("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4");
    public static final byte[] TOKEN_PROGRAM = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    public static final byte[] ATA_PROGRAM = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
    public static final int ROUTE_DATA_LENGTH = 40;

    private JupiterSwapFixture() {}

    /**
     * Static keys: 0 bot wallet (signer, fee payer, writable), 1 bot token account (writable), 2 synthetic output mint,
     * 3 System Program, 4 Compute Budget, 5 Token Program, 6 Associated Token Program, 7 aggregator program. Lookups
     * add 3 writable and 4 readonly route accounts from two synthetic tables.
     */
    public static byte[] message(byte[] botWallet) {
        byte[] botTokenAccount = TxBytes.filled(21);
        byte[] syntheticMint = TxBytes.filled(22);
        byte[] routeData = new byte[ROUTE_DATA_LENGTH];
        for (int i = 0; i < routeData.length; i++) {
            routeData[i] = (byte) (i * 7 + 3);
        }
        return TxBytes.message()
                .header(1, 0, 6)
                .key(botWallet)
                .key(botTokenAccount)
                .key(syntheticMint)
                .key(TxBytes.SYSTEM_PROGRAM)
                .key(TxBytes.COMPUTE_BUDGET_PROGRAM)
                .key(TOKEN_PROGRAM)
                .key(ATA_PROGRAM)
                .key(JUPITER_PROGRAM)
                .blockhash(TxBytes.filled(9))
                .instruction(4, new int[0], TxBytes.setComputeUnitLimit(280_000))
                .instruction(4, new int[0], TxBytes.setComputeUnitPrice(12_345L))
                .instruction(6, new int[] {0, 1, 0, 2, 3, 5}, new byte[] {1})
                .instruction(7, new int[] {5, 0, 1, 8, 9, 10, 11, 12, 13, 14, 2}, routeData)
                .addressTable(TxBytes.filled(31), new int[] {4, 7}, new int[] {1, 2})
                .addressTable(TxBytes.filled(32), new int[] {0}, new int[] {9, 15})
                .build();
    }
}
