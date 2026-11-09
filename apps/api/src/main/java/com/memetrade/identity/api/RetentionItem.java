package com.memetrade.identity.api;

import java.util.Objects;

/**
 * One row of the retention table shown in the privacy policy (L1 {@code RetentionItem}, J5): the data item, why it is
 * kept, for how long and what happens at the end. Plain text, never HTML.
 */
public record RetentionItem(String dataItem, String purpose, String period, String endAction) {

    public RetentionItem {
        Objects.requireNonNull(dataItem, "dataItem");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(endAction, "endAction");
    }
}
