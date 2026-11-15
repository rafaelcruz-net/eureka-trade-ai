package com.memetrade.txsigner.testsupport;

import java.time.Instant;

/**
 * Writes the canonical consent message of contract-summary.md ("Mensagem de consentimento") in TEST code, line by line,
 * independently of the production parser, with one setter per field so each attack changes only the field under test.
 */
public final class ConsentText {

    public static final String TITLE = "Eureka Trader - consentimento REAL v1";

    private String title = TITLE;
    private String purpose = "ACTIVATE";
    private String network = "LOCALNET";
    private String mainWallet;
    private String botWallet = "NEW";
    private String mode = "SIGNED";
    private String capLamports = "2000000000";
    private String nonce = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR";
    private Instant issuedAt = Instant.parse("2026-11-14T12:00:00Z");
    private Instant expiresAt = Instant.parse("2026-11-14T12:05:00Z");
    private String trailer = "";

    private ConsentText(String mainWallet) {
        this.mainWallet = mainWallet;
    }

    public static ConsentText activation(String mainWallet) {
        return new ConsentText(mainWallet);
    }

    public ConsentText title(String value) {
        this.title = value;
        return this;
    }

    public ConsentText purpose(String value) {
        this.purpose = value;
        return this;
    }

    public ConsentText network(String value) {
        this.network = value;
        return this;
    }

    public ConsentText mainWallet(String value) {
        this.mainWallet = value;
        return this;
    }

    public ConsentText botWallet(String value) {
        this.botWallet = value;
        return this;
    }

    public ConsentText mode(String value) {
        this.mode = value;
        return this;
    }

    public ConsentText capLamports(String value) {
        this.capLamports = value;
        return this;
    }

    public ConsentText nonce(String value) {
        this.nonce = value;
        return this;
    }

    public ConsentText issuedAt(Instant value) {
        this.issuedAt = value;
        return this;
    }

    public ConsentText expiresAt(Instant value) {
        this.expiresAt = value;
        return this;
    }

    /** Text appended after the last line (a trailing newline or space must be refused). */
    public ConsentText trailer(String value) {
        this.trailer = value;
        return this;
    }

    public String text() {
        return String.join(
                        "\n",
                        title,
                        "purpose: " + purpose,
                        "network: " + network,
                        "main-wallet: " + mainWallet,
                        "bot-wallet: " + botWallet,
                        "mode: " + mode,
                        "cap-lamports: " + capLamports,
                        "nonce: " + nonce,
                        "issued-at: " + issuedAt,
                        "expires-at: " + expiresAt)
                + trailer;
    }
}
