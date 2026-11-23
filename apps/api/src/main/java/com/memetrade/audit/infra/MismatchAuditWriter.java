package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.audit.domain.AuditEventValidator;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 261121-paper-real-unico U2 (NFR3.15, R-09, R-10; security-design.md "Sequência"): the dedicated writer of the event
 * of a reconciliation mismatch. In ONE transaction of the pool {@code pnl-mismatch-audit}, through the
 * {@code JdbcClient} of that pool (so the lock, the read and the insert run on the same connection and the lock only
 * leaves with the commit):
 *
 * <ol>
 *   <li>checks the deadline again right after the connection opened (it may have waited up to 250 ms), and gives up
 *       without writing when it passed; otherwise {@code set local statement_timeout} to what remains;
 *   <li>tries the advisory lock of the transaction {@code (0x504E4C4D "PNLM", hashtext(<person>:<fingerprint>))},
 *       never waiting: another instance writing the same event makes this one write nothing ({@code LOCK_BUSY});
 *   <li>asks the trail, in ONE statement (one snapshot), for an event of the same action, tenant, person and fingerprint
 *       in the last hour: in the pending outbox ({@code audit_outbox_pending_idx}) or in {@code audit_event}
 *       ({@code audit_event_action_time_id_idx}); one found writes nothing ({@code DEDUPLICATED});
 *   <li>otherwise inserts the event into the outbox ({@code WRITTEN}).
 * </ol>
 *
 * It reuses the {@link AuditEventValidator} and the {@link AuditPayloadCodec} of the trail and the counter
 * {@code audit_events_published} (after the commit), and never calls the {@code JdbcAuditEventPublisher}, which stays on
 * the main pool. Nothing is kept in memory: an api restarted keeps the limit.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class MismatchAuditWriter implements RateLimitedAuditPublisher {

    /** The space of the advisory lock, "PNLM" in ASCII; the one of the WalletLock is 0x55335253. */
    static final int LOCK_SPACE = 0x504E4C4D;

    static final Duration WINDOW = Duration.ofHours(1);

    private final MismatchAuditDataSource pool;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AuditEventValidator validator = new AuditEventValidator();
    private volatile LongConsumer backendPids = pid -> {};

    public MismatchAuditWriter(MismatchAuditDataSource pool, Clock clock, MeterRegistry registry) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Outcome publishOncePerHour(AuditEventCommand command, String fingerprint, Instant deadline) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(deadline, "deadline");
        if (!fingerprint.equals(command.details().get("fingerprint"))) {
            throw new IllegalArgumentException("the fingerprint of the event is in its details");
        }
        validator.validate(command);
        return pool.transactions().execute(status -> write(command, fingerprint, deadline));
    }

    private Outcome write(AuditEventCommand command, String fingerprint, Instant deadline) {
        if (!TransactionSynchronizationManager.hasResource(pool.dataSource())) {
            throw new IllegalStateException("the audit write runs only in a transaction of its own pool");
        }
        Duration left = Duration.between(clock.instant(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new DeadlinePassed();
        }
        pool.jdbc()
                .sql("set local statement_timeout = " + Math.max(1, left.toMillis()))
                .update();
        String actor = command.actor();
        Boolean locked = pool.jdbc()
                .sql("select pg_try_advisory_xact_lock(:space, hashtext(:key))")
                .param("space", LOCK_SPACE)
                .param("key", actor + ":" + fingerprint)
                .query(Boolean.class)
                .single();
        backendPids.accept(backendPid());
        if (!Boolean.TRUE.equals(locked)) {
            return Outcome.LOCK_BUSY;
        }
        Instant since = clock.instant().minus(WINDOW);
        String tenant = command.tenantId().map(Object::toString).orElse(null);
        Boolean found = pool.jdbc()
                .sql("""
                        select exists (
                                 select 1 from audit_outbox
                                 where delivered_at is null and created_at >= :since
                                   and payload ->> 'action' = :action and payload ->> 'tenantId' = :tenant
                                   and payload ->> 'actor' = :actor
                                   and payload -> 'details' ->> 'fingerprint' = :fingerprint)
                            or exists (
                                 select 1 from audit_event
                                 where action = :action and occurred_at >= :since
                                   and tenant_id = cast(:tenant as uuid) and actor = :actor
                                   and details ->> 'fingerprint' = :fingerprint)""")
                .param("since", Timestamp.from(since))
                .param("action", command.action())
                .param("tenant", tenant)
                .param("actor", actor)
                .param("fingerprint", fingerprint)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(found)) {
            return Outcome.DEDUPLICATED;
        }
        pool.jdbc()
                .sql("""
                        insert into audit_outbox (event_id, payload, created_at, delivered_at)
                        values (:id, cast(:payload as jsonb), :createdAt, null)
                        on conflict (event_id) do nothing""")
                .param("id", command.eventId())
                .param("payload", AuditPayloadCodec.encode(command))
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
        backendPids.accept(backendPid());
        Counter published = Counter.builder("audit_events_published")
                .description("Audit events written to the outbox in committed transactions")
                .tag("action", command.action())
                .register(registry);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                published.increment();
            }
        });
        return Outcome.WRITTEN;
    }

    private long backendPid() {
        return pool.jdbc().sql("select pg_backend_pid()").query(Long.class).single();
    }

    /**
     * Test hook (ReconciliationAuditLimitIT): receives the {@code pg_backend_pid()} of the lock and of the insert, which
     * must be the same connection.
     */
    public void observeBackendPids(LongConsumer observer) {
        this.backendPids = Objects.requireNonNull(observer, "observer");
    }
}
