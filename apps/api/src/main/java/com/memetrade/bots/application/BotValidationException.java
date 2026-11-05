package com.memetrade.bots.application;

import com.memetrade.bots.domain.Violation;
import java.util.List;

/** BR1.2 to BR1.5, NFR6.2: the refused fields of a step or of the limits; nothing is written (422). */
public class BotValidationException extends RuntimeException {

    private final List<Violation> violations;

    public BotValidationException(List<Violation> violations) {
        super("One or more fields are invalid.");
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
