package com.memetrade.botwallet.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Metrics of the bot wallet (observability-design.md, NFR5.1 to NFR5.4, NFR5.16): never a label with an address, a
 * tenant or a person. Counters of deposits by source and of withdrawals by state, the time to confirm, the unknown
 * deposits above 0.001 SOL, the reconciliation marks, and two gauges read from the database: the total balance of the
 * bot wallets and the age of the oldest unfinished withdrawal per state.
 */
@Component
public class RealMetrics {

    static final long UNKNOWN_DEPOSIT_THRESHOLD = 1_000_000L;
    private static final List<String> PENDING_STATES = List.of("REQUESTED", "SIGNED", "SENT", "AMBIGUOUS");

    private final MeterRegistry meters;
    private final JdbcClient jdbc;
    private final Clock clock;

    public RealMetrics(MeterRegistry meters, JdbcClient jdbc, Clock clock) {
        this.meters = meters;
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("realwallet_balance_lamports_total", this::totalBalance)
                .description("Sum of the REAL balances of every bot wallet, from the ledger")
                .register(meters);
        for (String state : PENDING_STATES) {
            Gauge.builder("realwallet_withdrawal_oldest_pending_seconds", () -> oldestPendingSeconds(state))
                    .tag("state", state)
                    .description("Age of the oldest unfinished withdrawal in this state")
                    .register(meters);
        }
    }

    public void deposit(boolean knownSource, long lamports) {
        Counter.builder("realwallet_deposits")
                .tag("source", knownSource ? "known" : "unknown")
                .register(meters)
                .increment();
        if (!knownSource) {
            DistributionSummary.builder("realwallet_unknown_deposit_lamports")
                    .baseUnit("lamports")
                    .register(meters)
                    .record(lamports);
            if (lamports > UNKNOWN_DEPOSIT_THRESHOLD) {
                Counter.builder("realwallet_unknown_deposit_over_threshold")
                        .register(meters)
                        .increment();
            }
        }
    }

    public void withdrawalState(String state, Instant requestedAt) {
        Counter.builder("realwallet_withdrawals")
                .tag("state", state)
                .register(meters)
                .increment();
        if ("CONFIRMED".equals(state)) {
            Timer.builder("realwallet_withdrawal")
                    .description("Time from the request of a withdrawal to its confirmation in finalized")
                    .register(meters)
                    .record(Duration.between(requestedAt, clock.instant()));
        }
    }

    public void reconciliationMark() {
        Counter.builder("realwallet_reconciliation_marks").register(meters).increment();
    }

    Long totalBalance() {
        return jdbc.sql("select coalesce(sum(lamports), 0) from real_ledger_entry "
                        + "where entry_kind not in ('RESERVE', 'RESERVE_RELEASE')")
                .query(Long.class)
                .single();
    }

    Long oldestPendingSeconds(String state) {
        Timestamp oldest = jdbc.sql("select min(updated_at) from withdrawal where state = :state")
                .param("state", state)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return oldest == null
                ? 0L
                : Duration.between(oldest.toInstant(), clock.instant()).toSeconds();
    }
}
