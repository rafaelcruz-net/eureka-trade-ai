package com.memetrade.audit.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login of the retention pool (security-design.md): a member of the fixed group role {@code audit_retention}, which
 * may only read and delete {@code audit_event}. Values come from {@code DB_RETENTION_USER} and
 * {@code DB_RETENTION_PASSWORD} in {@code .env}; the password never appears in {@link #toString()}.
 */
@ConfigurationProperties(prefix = "memetrade.audit.retention-db")
public record AuditRetentionDatabaseProperties(String username, String password) {

    public AuditRetentionDatabaseProperties {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
    }

    @Override
    public String toString() {
        return "AuditRetentionDatabaseProperties[username=" + username + ", password=***]";
    }
}
