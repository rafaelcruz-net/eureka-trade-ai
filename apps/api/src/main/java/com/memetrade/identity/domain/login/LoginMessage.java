package com.memetrade.identity.domain.login;

import com.memetrade.shared.wallet.WalletAddress;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Fixed line template of the login message (BR1.13). Lines, in order: domain request, wallet address, the statement
 * that the signature does not authorise operations, URI, Chain, Nonce, Issued At and Expiration Time.
 */
public record LoginMessage(
        String domain,
        String walletAddress,
        String uri,
        String chain,
        String nonce,
        Instant issuedAt,
        Instant expiresAt) {

    static final String REQUEST_SUFFIX = " pede que você entre com a sua conta Solana";
    static final String STATEMENT = "Esta assinatura serve só para entrar e não autoriza operações.";
    private static final int LINES = 8;
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    public String render() {
        return String.join(
                "\n",
                domain + REQUEST_SUFFIX,
                walletAddress,
                STATEMENT,
                "URI: " + uri,
                "Chain: " + chain,
                "Nonce: " + nonce,
                "Issued At: " + issuedAt,
                "Expiration Time: " + expiresAt);
    }

    /** Parses a received message; empty when it does not follow the template (MALFORMED_MESSAGE). */
    public static Optional<LoginMessage> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] lines = text.split("\n", -1);
        if (lines.length != LINES || !lines[0].endsWith(REQUEST_SUFFIX) || !STATEMENT.equals(lines[2])) {
            return Optional.empty();
        }
        String domain = lines[0].substring(0, lines[0].length() - REQUEST_SUFFIX.length());
        Optional<String> uri = value(lines[3], "URI: ");
        Optional<String> chain = value(lines[4], "Chain: ");
        Optional<String> nonce =
                value(lines[5], "Nonce: ").filter(n -> NONCE.matcher(n).matches());
        Optional<Instant> issuedAt = value(lines[6], "Issued At: ").flatMap(LoginMessage::instant);
        Optional<Instant> expiresAt = value(lines[7], "Expiration Time: ").flatMap(LoginMessage::instant);
        if (domain.isBlank()
                || !WalletAddress.isValid(lines[1])
                || uri.isEmpty()
                || chain.isEmpty()
                || nonce.isEmpty()
                || issuedAt.isEmpty()
                || expiresAt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LoginMessage(
                domain, lines[1], uri.get(), chain.get(), nonce.get(), issuedAt.get(), expiresAt.get()));
    }

    private static Optional<String> value(String line, String prefix) {
        if (!line.startsWith(prefix) || line.length() == prefix.length()) {
            return Optional.empty();
        }
        return Optional.of(line.substring(prefix.length()));
    }

    private static Optional<Instant> instant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException notAnInstant) {
            return Optional.empty();
        }
    }
}
