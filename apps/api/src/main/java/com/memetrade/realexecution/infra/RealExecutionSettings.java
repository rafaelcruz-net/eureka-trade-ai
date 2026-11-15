package com.memetrade.realexecution.infra;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Start-up check of the REAL execution in the api and in the worker (U4-NFR8.1, U4-NFR8.10, U4-NFR3.3, BR4.3). A
 * violation stops the process with a message naming the setting, never a value of a secret:
 *
 * <ul>
 *   <li>{@code MEMETRADE_REAL_NETWORK=MAINNET} only with the profile {@code production};
 *   <li>outside the profiles {@code test} and {@code local}, the Jupiter base URL is https on one of the fixed hosts of
 *       the Jupiter (the stub of the local Compose is accepted only there);
 *   <li>the times, the {@code maxAccounts} and the ceiling of the priority fee stay in their ranges.
 * </ul>
 */
public final class RealExecutionSettings {

    /** The hosts of the Jupiter swap API the worker may call outside the profiles test and local. */
    public static final Set<String> JUPITER_HOSTS = Set.of("lite-api.jup.ag", "api.jup.ag");

    /** BR4.3: the ceiling of the priority fee never passes 0.001 SOL. */
    public static final long MAX_PRIORITY_FEE_LAMPORTS = 1_000_000L;

    /** scheme, host, optional port, optional path; never a user, a query nor a fragment. */
    private static final Pattern URL =
            Pattern.compile("^(https?)://([A-Za-z0-9.-]+)(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$");

    private RealExecutionSettings() {}

    /**
     * @param lenient the profile {@code test} or {@code local} is active
     * @param production the profile {@code production} is active
     * @param network {@code memetrade.real.network}
     */
    public static List<String> violations(
            RealExecutionProperties properties, boolean lenient, boolean production, String network) {
        List<String> violations = new ArrayList<>();
        if ("MAINNET".equals(network) && !production) {
            violations.add("MEMETRADE_REAL_NETWORK=MAINNET needs the production profile (U4-NFR8.1)");
        }
        jupiter(properties.jupiter(), lenient).ifPresent(violations::add);
        if (properties.maxPriorityFeeLamports() < 0
                || properties.maxPriorityFeeLamports() > MAX_PRIORITY_FEE_LAMPORTS) {
            violations.add("memetrade.real.execution.max-priority-fee-lamports must be 0 to 1000000");
        }
        range(properties.quoteMaxAge(), Duration.ofSeconds(1), Duration.ofSeconds(10), "quote-max-age", violations);
        range(properties.signingWindow(), Duration.ofSeconds(1), Duration.ofSeconds(8), "signing-window", violations);
        range(
                properties.confirmationTimeout(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(120),
                "confirmation-timeout",
                violations);
        range(properties.lease(), Duration.ofSeconds(2), Duration.ofSeconds(30), "lease", violations);
        if (properties.upstreamFailuresToPause() < 1) {
            violations.add("memetrade.real.execution.upstream-failures-to-pause must be at least 1");
        }
        if (properties.decisionBatchSize() < 1 || properties.orderBatchSize() < 1) {
            violations.add("memetrade.real.execution batch sizes must be at least 1");
        }
        return violations;
    }

    private static java.util.Optional<String> jupiter(RealExecutionProperties.Jupiter jupiter, boolean lenient) {
        if (jupiter == null || jupiter.baseUrl() == null || jupiter.baseUrl().isBlank()) {
            return java.util.Optional.of("MEMETRADE_JUPITER_BASE_URL is missing");
        }
        if (jupiter.maxAccounts() < 8
                || jupiter.maxAccounts() > 64
                || jupiter.retryMaxAccounts() < 8
                || jupiter.retryMaxAccounts() >= jupiter.maxAccounts()) {
            return java.util.Optional.of(
                    "memetrade.real.execution.jupiter max-accounts must be 8 to 64, the retry smaller");
        }
        if (jupiter.timeout() == null
                || jupiter.timeout().compareTo(Duration.ofSeconds(2)) > 0
                || jupiter.timeout().isNegative()
                || jupiter.timeout().isZero()) {
            return java.util.Optional.of("memetrade.real.execution.jupiter.timeout must be up to 2 s (U4-NFR3.3)");
        }
        Matcher url = URL.matcher(jupiter.baseUrl().trim());
        if (!url.matches()) {
            return java.util.Optional.of("MEMETRADE_JUPITER_BASE_URL must be an http(s) URL without user or query");
        }
        if (lenient) {
            return java.util.Optional.empty();
        }
        if (!"https".equals(url.group(1)) || !JUPITER_HOSTS.contains(url.group(2)) || url.group(3) != null) {
            return java.util.Optional.of("MEMETRADE_JUPITER_BASE_URL must be https on a fixed Jupiter host");
        }
        return java.util.Optional.empty();
    }

    private static void range(Duration value, Duration min, Duration max, String key, List<String> violations) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            violations.add("memetrade.real.execution." + key + " must be " + min + " to " + max);
        }
    }
}
