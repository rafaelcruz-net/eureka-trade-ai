package com.memetrade.administration.infra;

import com.memetrade.administration.domain.ActiveBots;
import com.memetrade.administration.domain.KillSwitchService;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotState;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.shared.config.ProcessRole;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the kill switch of U8 in the API process only (logical-components.md "Isolamento": the worker runs nothing of
 * U8). The apply lock has its OWN pool of one connection with the application login (performance-design.md): it never
 * competes with the requests for a connection and never shares a session with them, so the advisory lock is never
 * re-entered by accident.
 */
@Configuration(proxyBeanMethods = false)
@ProcessRole(ProcessRole.Kind.API)
public class AdministrationConfig {

    @Bean
    public ApplyLock applyLock(DataSourceProperties main, AdminProperties properties) {
        AdminProperties.KillSwitch settings = properties.killSwitch();
        HikariConfig config = new HikariConfig();
        config.setPoolName("memetrade-kill-switch-lock-pool");
        config.setJdbcUrl(main.determineUrl());
        config.setUsername(main.determineUsername());
        config.setPassword(main.determinePassword());
        config.setMaximumPoolSize(1);
        // 0: the connection opens on the first application and is retired after the idle timeout, so the lock pool
        // never keeps a database session open between kill switch changes and reconciliation rounds.
        config.setMinimumIdle(0);
        // Never the default 30 s: the whole wait of the lock fits in one budget (NFR2.3); ApplyLock lowers it further
        // to what is left of the budget before each borrow.
        config.setConnectionTimeout(settings.applyLockWait().toMillis());
        config.setAutoCommit(true);
        // -1: a database still starting never stops the api; the first application opens the connection.
        config.setInitializationFailTimeout(-1);
        return new ApplyLock(new HikariDataSource(config), settings.applyLockWait(), settings.applyLockPoll());
    }

    @Bean
    public KillSwitchApplier killSwitchApplier(
            ApplyLock applyLock,
            KillSwitchStore store,
            BotAdministration bots,
            KillSwitchMetrics metrics,
            AdminProperties properties) {
        return new KillSwitchApplier(
                applyLock, store, bots, metrics, properties.killSwitch().applyAttempts());
    }

    /**
     * BR3.1: the six reads of the system status, each through the public interface of its owner module, on a pool of
     * one thread per part (NFR10.4, daemon threads). The pool is NOT a bean of type Executor, so the task executor of
     * Spring Boot is untouched; it is shut down with the assembler.
     */
    @Bean(destroyMethod = "close")
    public com.memetrade.administration.domain.SystemStatusAssembler systemStatusAssembler(
            com.memetrade.identity.api.IdentityAdministration identity,
            BotAdministration bots,
            KillSwitchStore store,
            com.memetrade.marketdata.api.MarketDataQuery market,
            com.memetrade.audit.api.AuditBacklog backlog,
            com.memetrade.riskcontrol.api.RejectionCounts rejections,
            Clock clock) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService systemStatusPool =
                java.util.concurrent.Executors.newFixedThreadPool(6, runnable -> {
                    Thread thread = new Thread(runnable, "system-status-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        return new com.memetrade.administration.domain.SystemStatusAssembler(
                new com.memetrade.administration.domain.SystemStatusAssembler.Reads(
                        identity::counts,
                        bots::countByState,
                        store::read,
                        market::sourceStatuses,
                        backlog::pending,
                        rejections::countRejectedSince),
                systemStatusPool,
                java.time.Duration.ofMillis(500),
                clock);
    }

    /** BR2.7: ACTIVE bots of every tenant, read from C16 without identifiers. */
    @Bean
    public ActiveBots activeBots(BotAdministration bots) {
        return () -> bots.countByState().getOrDefault(BotState.ACTIVE, 0L);
    }

    @Bean
    public KillSwitchService killSwitchService(
            KillSwitchStore store,
            ActiveBots activeBots,
            AuditEventPublisher audit,
            IdempotencyGuard idempotency,
            Clock clock) {
        return new KillSwitchService(store, activeBots, audit, idempotency, clock);
    }
}
