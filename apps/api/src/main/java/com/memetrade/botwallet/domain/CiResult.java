package com.memetrade.botwallet.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR1.4, security-design.md "CI", U5-NFR4.3): what the CI of the commit of the image says.
 * {@code GREEN} only with one run concluded {@code success}; {@code RED} when the CI answered without it (no run, in
 * progress, cancelled or failed); {@code UNREACHABLE} when it did not answer (network, HTTP, timeout, no SHA). Only the
 * id of the run is kept, never a token nor a URL.
 */
public record CiResult(Status status, Optional<String> runRef) {

    public enum Status {
        GREEN,
        RED,
        UNREACHABLE
    }

    public CiResult {
        Objects.requireNonNull(status, "status");
        runRef = runRef == null ? Optional.empty() : runRef;
    }

    public static CiResult green(String runRef) {
        return new CiResult(Status.GREEN, Optional.of(runRef));
    }

    public static CiResult red() {
        return new CiResult(Status.RED, Optional.empty());
    }

    public static CiResult unreachable() {
        return new CiResult(Status.UNREACHABLE, Optional.empty());
    }

    public boolean isGreen() {
        return status == Status.GREEN;
    }
}
