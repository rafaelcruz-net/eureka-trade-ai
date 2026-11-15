package com.memetrade.txsigner.policy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.CodecException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Checks a signed consent again inside the TxSigner, without trusting the api (BR1.5, BR1.8, BR5.3, NFR1.8): the
 * whole canonical text, the purpose of the request, the bot wallet line, the network of this TxSigner, the absolute cap
 * of 10 SOL, the validity of exactly five minutes, the Ed25519 signature of the main wallet with the native JDK, and a
 * nonce never used before. Pure: the nonce is only looked up here and is recorded with the policy change.
 */
public final class ConsentVerifier {

    public static final Duration VALIDITY = Duration.ofMinutes(5);
    /** Tolerance for a clock of the api slightly ahead of the TxSigner. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final Clock clock;
    private final Ed25519Verifier signatures;
    private final String network;
    private final long maxCapLamports;
    private final Predicate<String> isNonceUsed;

    public ConsentVerifier(
            Clock clock,
            Ed25519Verifier signatures,
            String network,
            long maxCapLamports,
            Predicate<String> isNonceUsed) {
        this.clock = clock;
        this.signatures = signatures;
        this.network = network;
        this.maxCapLamports = maxCapLamports;
        this.isNonceUsed = isNonceUsed;
    }

    public VerifiedConsent verify(ConsentCheck check) {
        ConsentMessage message =
                ConsentMessage.parse(check.message()).orElseThrow(() -> refuse(ConsentRefusal.MALFORMED));
        checkValidityWindow(message);
        checkFields(message, check);
        checkSignature(check);
        if (isNonceUsed.test(message.nonce())) {
            throw refuse(ConsentRefusal.NONCE_USED);
        }
        return new VerifiedConsent(
                message.purpose(),
                message.mode(),
                message.capLamports(),
                message.nonce(),
                message.expiresAt(),
                digest(check.message()));
    }

    private void checkValidityWindow(ConsentMessage message) {
        Instant now = clock.instant();
        if (!message.expiresAt().equals(message.issuedAt().plus(VALIDITY))
                || message.issuedAt().isAfter(now.plus(CLOCK_SKEW))) {
            throw refuse(ConsentRefusal.MALFORMED);
        }
        if (!now.isBefore(message.expiresAt())) {
            throw refuse(ConsentRefusal.EXPIRED);
        }
    }

    private void checkFields(ConsentMessage message, ConsentCheck check) {
        if (message.purpose() != check.expectedPurpose()) {
            throw refuse(ConsentRefusal.PURPOSE_MISMATCH);
        }
        if (!network.equals(message.network())) {
            throw refuse(ConsentRefusal.NETWORK_MISMATCH);
        }
        if (!message.mainWallet().equals(check.expectedMainWallet())) {
            throw refuse(ConsentRefusal.MAIN_WALLET_MISMATCH);
        }
        if (!message.botWallet().equals(check.expectedBotWallet())) {
            throw refuse(ConsentRefusal.BOT_WALLET_MISMATCH);
        }
        if (message.purpose() == ConsentPurpose.ACTIVATE && message.mode() != OperationMode.SIGNED) {
            throw refuse(ConsentRefusal.MODE_NOT_ALLOWED);
        }
        if (message.capLamports() > maxCapLamports) {
            throw refuse(ConsentRefusal.CAP_ABOVE_MAXIMUM);
        }
    }

    private void checkSignature(ConsentCheck check) {
        byte[] publicKey;
        byte[] signature;
        try {
            publicKey = Base58.decodeExact(check.expectedMainWallet(), 32);
            signature = Base58.decodeExact(check.signature(), Ed25519Verifier.SIGNATURE_LENGTH);
        } catch (CodecException malformed) {
            throw refuse(ConsentRefusal.BAD_SIGNATURE);
        }
        if (!signatures.verify(publicKey, check.message().getBytes(StandardCharsets.UTF_8), signature)) {
            throw refuse(ConsentRefusal.BAD_SIGNATURE);
        }
    }

    /** SHA-256 hex of the exact UTF-8 bytes of the consent text. */
    public static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static ConsentRefusedException refuse(ConsentRefusal refusal) {
        return new ConsentRefusedException(refusal);
    }
}
