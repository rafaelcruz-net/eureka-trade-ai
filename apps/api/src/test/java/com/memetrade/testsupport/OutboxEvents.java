package com.memetrade.testsupport;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the audit events a test produced from {@code audit_outbox} (C9), by action and target or correlation id. */
public final class OutboxEvents {

    private OutboxEvents() {}

    public static long count(JdbcClient jdbc, String action, String targetRef) {
        Long count = jdbc.sql("""
                        select count(*) from audit_outbox
                        where payload->>'action' = :action and payload->>'targetRef' = :target
                        """)
                .param("action", action)
                .param("target", targetRef)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Every payload (as JSON text) of one action, to scan or to read details. */
    public static List<String> payloads(JdbcClient jdbc, String action) {
        return jdbc.sql("select payload::text from audit_outbox where payload->>'action' = :action")
                .param("action", action)
                .query(String.class)
                .list();
    }

    /** Events of one action written for one wallet address (LOGIN_REJECTED keeps it in details). */
    public static List<String> reasonsForWallet(JdbcClient jdbc, String action, String walletAddress) {
        return jdbc.sql("""
                        select payload->'details'->>'reason' from audit_outbox
                        where payload->>'action' = :action and payload->'details'->>'walletAddress' = :wallet
                        order by created_at
                        """)
                .param("action", action)
                .param("wallet", walletAddress)
                .query(String.class)
                .list();
    }
}
