package com.memetrade.botwallet.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The canonical consent message of contract-summary.md, built only by the server (BR1.5): ten {@code key: value} lines
 * separated by LF, no trailing space nor extra line. The TxSigner rebuilds and compares the same text byte by byte, so
 * the issue instant is kept in whole seconds.
 */
public final class ConsentMessages {

    public static final String TITLE = "Eureka Trader - consentimento REAL v1";
    public static final Duration VALIDITY = Duration.ofMinutes(5);
    public static final String NEW_WALLET = "NEW";

    private ConsentMessages() {}

    public record Fields(
            ConsentKind kind,
            Network network,
            String mainWallet,
            String botWallet,
            OperationMode mode,
            long capLamports,
            String nonce,
            Instant issuedAt) {}

    public static String render(Fields fields) {
        Instant issued = fields.issuedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return String.join(
                "\n",
                TITLE,
                "purpose: " + fields.kind(),
                "network: " + fields.network(),
                "main-wallet: " + fields.mainWallet(),
                "bot-wallet: " + (fields.botWallet() == null ? NEW_WALLET : fields.botWallet()),
                "mode: " + fields.mode(),
                "cap-lamports: " + fields.capLamports(),
                "nonce: " + fields.nonce(),
                "issued-at: " + issued,
                "expires-at: " + issued.plus(VALIDITY));
    }
}
