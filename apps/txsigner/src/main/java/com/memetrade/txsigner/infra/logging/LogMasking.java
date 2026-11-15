package com.memetrade.txsigner.infra.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log masking of the TxSigner (security-design.md "Dados e logs", NFR1.10): any Base58 run of 32 to 90 characters
 * (an address or a transaction signature) keeps only its first six and its last four characters.
 */
public final class LogMasking {

    private static final String B58 = "[1-9A-HJ-NP-Za-km-z]";
    private static final Pattern BASE58_RUN =
            Pattern.compile("(?<!" + B58 + ")(" + B58 + "{6})" + B58 + "{22,80}(" + B58 + "{4})(?!" + B58 + ")");

    private LogMasking() {}

    public static String mask(String text) {
        if (text == null || text.length() < 32) {
            return text;
        }
        Matcher matcher = BASE58_RUN.matcher(text);
        return matcher.find() ? matcher.replaceAll("$1…$2") : text;
    }
}
