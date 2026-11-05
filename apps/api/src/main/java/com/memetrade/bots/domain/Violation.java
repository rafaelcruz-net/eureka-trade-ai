package com.memetrade.bots.domain;

import java.util.Objects;

/**
 * One refused field of the wizard or of the limits (BR1.2 to BR1.5): the field path, the code of C4 and a short pt-BR
 * message shown next to the field.
 */
public record Violation(String field, Code code, String message) {

    public enum Code {
        VALIDATION_FAILED,
        LIMIT_INCOHERENT,
        VIRTUAL_BALANCE_ABOVE_CAP
    }

    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
