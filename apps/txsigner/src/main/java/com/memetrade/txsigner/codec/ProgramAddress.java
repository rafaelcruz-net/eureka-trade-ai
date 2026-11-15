package com.memetrade.txsigner.codec;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Own derivation of program derived addresses (U4, BR4.3: "conta de destino que não pertença à carteira do bot"), with
 * no Solana SDK (project.md): SHA-256 of the seeds, the bump, the program id and the marker
 * {@code ProgramDerivedAddress}, from bump 255 down to the first hash that is NOT a point of the Ed25519 curve. The
 * TxSigner has no network, so it proves that a token account belongs to the bot wallet by deriving the associated token
 * account itself. Checked against the SDK vectors ({@code SolanaVectorConformanceTest}).
 */
public final class ProgramAddress {

    public static final byte[] ASSOCIATED_TOKEN_PROGRAM = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

    private static final byte[] MARKER = "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121_665)
            .multiply(BigInteger.valueOf(121_666).modInverse(P))
            .mod(P);
    private static final BigInteger EULER = P.subtract(BigInteger.ONE).shiftRight(1);

    private ProgramAddress() {}

    /** The associated token account of {@code owner} for {@code mint} under {@code tokenProgram}. */
    public static byte[] associatedTokenAccount(byte[] owner, byte[] tokenProgram, byte[] mint) {
        return find(List.of(owner, tokenProgram, mint), ASSOCIATED_TOKEN_PROGRAM);
    }

    /** The first off-curve address of {@code seeds} and {@code programId}, from bump 255 down. */
    public static byte[] find(List<byte[]> seeds, byte[] programId) {
        for (int bump = 255; bump >= 0; bump--) {
            MessageDigest sha = sha256();
            seeds.forEach(sha::update);
            sha.update((byte) bump);
            sha.update(programId);
            sha.update(MARKER);
            byte[] candidate = sha.digest();
            if (!isOnCurve(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No program derived address for these seeds");
    }

    /**
     * Whether 32 bytes decompress to a point of the Ed25519 curve, like the decompression of the runtime: the
     * y-coordinate (little-endian, top bit cleared, reduced) has an x with x^2 = (y^2 - 1) / (d y^2 + 1).
     */
    public static boolean isOnCurve(byte[] bytes) {
        if (bytes == null || bytes.length != 32) {
            return false;
        }
        byte[] bigEndian = new byte[32];
        for (int i = 0; i < 32; i++) {
            bigEndian[i] = bytes[31 - i];
        }
        bigEndian[0] &= 0x7F;
        BigInteger y = new BigInteger(1, bigEndian).mod(P);
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        if (v.signum() == 0) {
            return u.signum() == 0;
        }
        BigInteger ratio = u.multiply(v.modInverse(P)).mod(P);
        if (ratio.signum() == 0) {
            return true;
        }
        return ratio.modPow(EULER, P).equals(BigInteger.ONE);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
