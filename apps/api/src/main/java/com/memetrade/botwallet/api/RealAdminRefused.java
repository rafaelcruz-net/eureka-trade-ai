package com.memetrade.botwallet.api;

import java.util.Objects;

/**
 * 261113-trading-real-bot U5 (R4, BR1.2 to BR6.1): a refusal of an administrative operation of the REAL, with the stable
 * machine code of the ProblemDetail ({@code FOUNDER_FIRST}, {@code PILOT_FULL}, {@code CAP_ONLY_LOWERED}...) and its
 * HTTP status. The message names the code only, never a value of the person.
 */
public class RealAdminRefused extends RuntimeException {

    private final String code;
    private final int status;

    public RealAdminRefused(String code, int status) {
        super("REAL administration refused: " + code);
        this.code = Objects.requireNonNull(code, "code");
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static RealAdminRefused validation() {
        return new RealAdminRefused("VALIDATION_FAILED", 422);
    }

    public static RealAdminRefused notFound() {
        return new RealAdminRefused("NOT_FOUND", 404);
    }
}
