package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.Network;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5 (BR1.3, BR1.5, U5-NFR1.1, U5-NFR8.2, S8, S28): the start-up checks of the REAL operations in
 * the api and in the worker. A violation stops the process with a message naming the setting, never a value of a
 * secret. The MAINNET needs the production profile, the founder, the SHA of the image and the GitHub as the source of
 * the CI (a configured result is refused there); the worker also needs the repository and its read-only token.
 */
public final class RealOpsStartupChecks {

    /** The values a configured CI accepts outside the MAINNET. */
    public static final Set<String> CONFIGURED_CI = Set.of("GREEN", "RED");

    private RealOpsStartupChecks() {}

    /** What the checks read. */
    public record Inputs(
            Network network,
            boolean production,
            boolean worker,
            String founderUserId,
            String ci,
            String buildSha,
            String githubRepository,
            String githubToken) {}

    public static List<String> violations(Inputs in) {
        List<String> violations = new ArrayList<>();
        boolean configuredCi = in.ci() != null && !in.ci().isBlank();
        if (configuredCi && !CONFIGURED_CI.contains(in.ci().trim())) {
            violations.add("MEMETRADE_REAL_OPS_CI must be GREEN or RED (or empty to read the GitHub)");
        }
        boolean founderPresent =
                in.founderUserId() != null && !in.founderUserId().isBlank();
        if (founderPresent && !isUuid(in.founderUserId().trim())) {
            violations.add("memetrade.real.founder-user-id must be a UUID");
        }
        if (in.network() != Network.MAINNET) {
            return violations;
        }
        if (!in.production()) {
            violations.add("MEMETRADE_REAL_NETWORK=MAINNET needs the production profile (BR1.5, U5-NFR8.2)");
        }
        if (!founderPresent) {
            violations.add("memetrade.real.founder-user-id is required with the MAINNET (BR1.3)");
        }
        if (configuredCi) {
            violations.add("MEMETRADE_REAL_OPS_CI is refused with the MAINNET: the CI is read from the GitHub (BR1.4)");
        }
        if (in.buildSha() == null || in.buildSha().isBlank()) {
            violations.add("MEMETRADE_BUILD_SHA is required with the MAINNET (security-design.md)");
        }
        if (in.worker() && (blank(in.githubRepository()) || blank(in.githubToken()))) {
            violations.add("the worker needs MEMETRADE_GITHUB_REPOSITORY and MEMETRADE_GITHUB_ACTIONS_TOKEN with the"
                    + " MAINNET (U5-NFR1.1)");
        }
        return violations;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
