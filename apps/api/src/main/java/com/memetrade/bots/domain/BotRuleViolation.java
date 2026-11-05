package com.memetrade.bots.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * A domain refusal of a lifecycle command (BR2.1 to BR2.4): nothing changes and no event is written. The web layer maps
 * each code to its 409 problem; a job never sees one for a transition it is allowed to make. {@code value} carries the
 * figure the screen shows with the refusal (the loss of the day of {@link Code#DAILY_LOSS_LIMIT_REACHED}).
 */
public class BotRuleViolation extends RuntimeException {

    public enum Code {
        /** BR2.1: a transition outside the table of the annex. */
        INVALID_TRANSITION,
        /** BR2.2, BR2.4: the person already has 3 ACTIVE bots. */
        ACTIVE_BOT_LIMIT,
        /** BR2.2, BR2.4: the kill switch is on, or the bot still has the SYSTEM reason. */
        PAUSED_BY_SYSTEM,
        /** BR2.4: after the daily loss, the limit in force is not above the loss of the UTC day. */
        DAILY_LOSS_LIMIT_REACHED
    }

    private final Code code;
    private final String value;

    public BotRuleViolation(Code code, String detail) {
        this(code, detail, null);
    }

    public BotRuleViolation(Code code, String detail, String value) {
        super(detail);
        this.code = Objects.requireNonNull(code, "code");
        this.value = value;
    }

    public Code code() {
        return code;
    }

    public Optional<String> value() {
        return Optional.ofNullable(value);
    }
}
