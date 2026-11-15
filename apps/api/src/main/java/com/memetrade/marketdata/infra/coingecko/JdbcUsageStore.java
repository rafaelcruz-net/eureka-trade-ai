package com.memetrade.marketdata.infra.coingecko;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UsageStore} over {@code marketdata_provider_usage}. Each statement is an atomic upsert in its OWN short
 * transaction ({@code REQUIRES_NEW}): the call is counted and committed BEFORE it is opened, so a call interrupted in
 * flight is already counted (infrastructure-specification.md "Contador").
 */
public class JdbcUsageStore implements UsageStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcUsageStore(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(2);
    }

    @Override
    public OptionalLong increment(String source, String month, int stopPercent, long budget, Instant now) {
        Long calls = transactions.execute(status -> jdbc.sql("""
                        insert into marketdata_provider_usage (source, month, calls, updated_at)
                        values (:source, :month, 1, :now)
                        on conflict (source, month) do update
                          set calls = marketdata_provider_usage.calls + 1, updated_at = excluded.updated_at
                          where marketdata_provider_usage.calls * 100 < :percent * :budget
                        returning calls
                        """)
                .param("source", source)
                .param("month", month)
                .param("now", Timestamp.from(now))
                .param("percent", (long) stopPercent)
                .param("budget", budget)
                .query(Long.class)
                .optional()
                .orElse(null));
        return calls == null ? OptionalLong.empty() : OptionalLong.of(calls);
    }

    @Override
    public long raiseTo(String source, String month, long calls, Instant now) {
        Long stored = transactions.execute(status -> jdbc.sql("""
                        insert into marketdata_provider_usage (source, month, calls, updated_at)
                        values (:source, :month, :calls, :now)
                        on conflict (source, month) do update
                          set calls = greatest(marketdata_provider_usage.calls, excluded.calls),
                              updated_at = excluded.updated_at
                        returning calls
                        """)
                .param("source", source)
                .param("month", month)
                .param("calls", calls)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single());
        return stored == null ? calls : stored;
    }

    @Override
    public long calls(String source, String month) {
        Long calls = transactions.execute(status -> jdbc.sql(
                        "select calls from marketdata_provider_usage where source = :source and month = :month")
                .param("source", source)
                .param("month", month)
                .query(Long.class)
                .optional()
                .orElse(0L));
        return calls == null ? 0 : calls;
    }
}
