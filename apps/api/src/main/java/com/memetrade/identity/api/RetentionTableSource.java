package com.memetrade.identity.api;

import java.util.List;

/**
 * J5 (contract-summary.md): the source of the retention table that L1 shows in the privacy policy (BR1.8). Identity
 * owns the port and consumes it; until U2 its default implementation returns the table declared by the current
 * version of the legal catalog. Another module (Privacy, from U2) registers its own implementation, which then wins:
 * the default is registered only when no other bean of this type exists.
 */
public interface RetentionTableSource {

    /** The rows of the table, in display order; empty only when the current version is legacy and declares none. */
    List<RetentionItem> items();
}
