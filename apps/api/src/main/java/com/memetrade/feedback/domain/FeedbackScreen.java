package com.memetrade.feedback.domain;

import java.util.Optional;

/** The screen of the guest app a comment comes from (entities.md FeedbackEntry.screen, mockups S1 to S9). */
public enum FeedbackScreen {
    S1,
    S2,
    S3,
    S4,
    S5,
    S6,
    S7,
    S8,
    S9;

    /** The screen named exactly {@code S1} to {@code S9}; anything else is empty (refused with 400 on the field). */
    public static Optional<FeedbackScreen> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (FeedbackScreen screen : values()) {
            if (screen.name().equals(raw)) {
                return Optional.of(screen);
            }
        }
        return Optional.empty();
    }
}
