package com.memetrade.privacy.domain;

import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionTerm;
import java.util.Objects;
import java.util.Optional;

/**
 * The period of a row of the retention catalog (entities.md RetentionPeriod): a fixed term from an anchor, or "while the
 * account exists". {@code label} is the text of the policy table, in Brazilian Portuguese.
 */
public record RetentionPeriod(Kind kind, Optional<RetentionTerm> term, Optional<RetentionAnchor> anchor, String label) {

    /** FIXED has a term and an anchor; WHILE_ACCOUNT_EXISTS has neither. */
    public enum Kind {
        FIXED,
        WHILE_ACCOUNT_EXISTS
    }

    public RetentionPeriod {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(anchor, "anchor");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("A retention period needs the label of the policy");
        }
        boolean fixed = kind == Kind.FIXED;
        if (fixed != term.isPresent() || fixed != anchor.isPresent()) {
            throw new IllegalArgumentException("A FIXED period has a term and an anchor; any other has neither");
        }
    }

    public static RetentionPeriod fixed(RetentionTerm term, RetentionAnchor anchor, String label) {
        return new RetentionPeriod(Kind.FIXED, Optional.of(term), Optional.of(anchor), label);
    }

    public static RetentionPeriod whileAccountExists(String label) {
        return new RetentionPeriod(Kind.WHILE_ACCOUNT_EXISTS, Optional.empty(), Optional.empty(), label);
    }

    /** {@code 30 days from ENDED} or {@code while the account exists}: the form of the conformance messages. */
    public String describe() {
        return kind == Kind.FIXED ? term.orElseThrow() + " from " + anchor.orElseThrow() : "while the account exists";
    }
}
