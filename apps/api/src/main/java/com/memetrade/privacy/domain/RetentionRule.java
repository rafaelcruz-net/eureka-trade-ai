package com.memetrade.privacy.domain;

import com.memetrade.identity.api.RetentionItem;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One row of the retention catalog (entities.md RetentionRule): the single source of the policy table (BR2.9) and of the
 * conformance check (BR2.10 to BR2.12). Immutable: changing a row is changing the code and the policy together.
 *
 * @param id stable upper-case identifier, for example {@code LOGIN_CHALLENGES}
 * @param order display order in the policy, from 1
 * @param dataItem, purpose, endAction the texts of the policy table, in Brazilian Portuguese
 */
public record RetentionRule(
        String id,
        int order,
        String dataItem,
        String purpose,
        RetentionPeriod period,
        String endAction,
        Enforcer enforcer) {

    private static final Pattern ID = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    public RetentionRule {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A retention rule id is upper case with underscores: " + id);
        }
        if (order < 1) {
            throw new IllegalArgumentException("The order of " + id + " starts at 1");
        }
        requireText(dataItem, id, "dataItem");
        requireText(purpose, id, "purpose");
        requireText(endAction, id, "endAction");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(enforcer, "enforcer");
        if (enforcer.kind() == Enforcer.Kind.GUARD && period.kind() != RetentionPeriod.Kind.WHILE_ACCOUNT_EXISTS) {
            throw new IllegalArgumentException("GUARD holds only while the account exists: " + id);
        }
    }

    /** The row as the J5 port shows it in the privacy policy. */
    public RetentionItem toItem() {
        return new RetentionItem(dataItem, purpose, period.label(), endAction);
    }

    private static void requireText(String value, String id, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The " + field + " of " + id + " is required");
        }
    }
}
