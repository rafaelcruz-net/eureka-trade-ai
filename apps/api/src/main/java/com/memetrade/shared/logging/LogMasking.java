package com.memetrade.shared.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second masking barrier (security-design.md, Q9): any Base58 run of 32 to 44 characters in a log value is reduced
 * to its first and last 4 characters. Over-masking another Base58-looking text is accepted (no business data lost).
 */
public final class LogMasking {

    private static final String B58 = "[1-9A-HJ-NP-Za-km-z]";
    private static final Pattern BASE58_RUN =
            Pattern.compile("(?<!" + B58 + ")(" + B58 + "{4})" + B58 + "{24,36}(" + B58 + "{4})(?!" + B58 + ")");

    private LogMasking() {}

    public static String maskBase58(String text) {
        if (text == null || text.length() < 32) {
            return text;
        }
        Matcher matcher = BASE58_RUN.matcher(text);
        return matcher.find() ? matcher.replaceAll("$1…$2") : text;
    }
}
