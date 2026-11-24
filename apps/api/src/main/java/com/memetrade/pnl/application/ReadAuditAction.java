package com.memetrade.pnl.application;

import com.memetrade.audit.api.AuditActions;

/**
 * 261121-paper-real-unico U3 (decision D-NFR1 of the founder; NFR3.13): the refusals of the reads of the P&L that are
 * audited, each with the action of the trail it writes. A 404 of an id that does not exist is never audited.
 */
public enum ReadAuditAction {
    /** A 404 of C5 or C6 for a bot of ANOTHER tenant. */
    TENANT_MISMATCH(AuditActions.TENANT_MISMATCH),
    /** A 422 of C6: two bots of the person with different strategies. */
    TEMPLATE_MISMATCH(AuditActions.PNL_COMPARE_TEMPLATE_MISMATCH);

    private final String auditAction;

    ReadAuditAction(String auditAction) {
        this.auditAction = auditAction;
    }

    /** The action written in the audit trail. */
    public String auditAction() {
        return auditAction;
    }
}
