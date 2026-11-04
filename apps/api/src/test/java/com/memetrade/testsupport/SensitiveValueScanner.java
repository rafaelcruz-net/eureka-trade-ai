package com.memetrade.testsupport;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sweep of the test suite (F6, BR4.1, BR4.2, NFR7.3): the tests register the sensitive values they emit (access and
 * refresh tokens, nonces, login signatures, full wallet addresses) and the sweep looks for them, for any JWT, for the
 * {@code Authorization} header name and for the {@code Bearer} prefix in each scanned place. A finding names the
 * place and the kind of value, NEVER the value itself, so the report can be printed by the build.
 *
 * <p>The full wallet address is allowed only where the caller says so ({@code audit_event.details}, BR2.4).
 */
public final class SensitiveValueScanner {

    /** Kinds of forbidden values. */
    public enum Kind {
        ACCESS_TOKEN,
        REFRESH_TOKEN,
        NONCE,
        SIGNATURE,
        WALLET_ADDRESS,
        /** U3 (BR2.3, NFR6.4): the full invitation code; never allowed anywhere, not even in audit details. */
        INVITE_CODE,
        JWT,
        AUTHORIZATION_HEADER,
        BEARER_PREFIX
    }

    /** One finding: where and what kind, never the value. */
    public record Finding(String location, Kind kind) {
        @Override
        public String toString() {
            return location + ": " + kind;
        }
    }

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern AUTHORIZATION = Pattern.compile("\\bAuthorization\\b");
    private static final Pattern BEARER = Pattern.compile("\\bBearer\\s+\\S");

    private final Map<Kind, Set<String>> registered = new EnumMap<>(Kind.class);

    /** Registers a value the test emitted; blank values are ignored. */
    public SensitiveValueScanner register(Kind kind, String value) {
        Objects.requireNonNull(kind, "kind");
        if (value != null && !value.isBlank()) {
            registered.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(value);
        }
        return this;
    }

    /** Scans one place where the full wallet address is NOT allowed. */
    public List<Finding> scan(String location, String text) {
        return scan(location, text, false);
    }

    /**
     * Scans one place.
     *
     * @param addressAllowed true only for {@code audit_event.details}, where the full address may appear (BR2.4)
     */
    public List<Finding> scan(String location, String text, boolean addressAllowed) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return findings;
        }
        registered.forEach((kind, values) -> {
            if (kind == Kind.WALLET_ADDRESS && addressAllowed) {
                return;
            }
            if (values.stream().anyMatch(text::contains)) {
                findings.add(new Finding(location, kind));
            }
        });
        if (JWT.matcher(text).find()) {
            findings.add(new Finding(location, Kind.JWT));
        }
        if (AUTHORIZATION.matcher(text).find()) {
            findings.add(new Finding(location, Kind.AUTHORIZATION_HEADER));
        }
        if (BEARER.matcher(text).find()) {
            findings.add(new Finding(location, Kind.BEARER_PREFIX));
        }
        return findings;
    }

    /** Printable report: one line per finding, with place and kind only. */
    public static String report(List<Finding> findings) {
        return findings.isEmpty()
                ? "no sensitive value found"
                : findings.stream().map(Finding::toString).collect(Collectors.joining("\n"));
    }
}
