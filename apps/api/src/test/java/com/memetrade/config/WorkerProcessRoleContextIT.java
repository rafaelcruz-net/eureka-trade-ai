package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Passo 1 of U5 (C20, NFR10.9, infrastructure-specification.md): the worker starts WITHOUT any web variable (no
 * signing key, no app origin, no trusted proxies, no administrator wallets, no retention login), runs only the bot
 * jobs, has no controller and no request filter, and binds the production cycle authentication context.
 */
class WorkerProcessRoleContextIT extends AbstractU5WorkerIT {

    /** The schedules of the slot dispatch with the closing check, the midnight and the purge (Passos 10 and 11). */
    static final java.util.Set<String> WORKER_JOBS = java.util.Set.of("BotSchedules");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private AuthContext auth;

    @Test
    void theWorkerStartsWithoutAnyWebSecret() {
        assertThat(environment.getProperty("memetrade.auth.access-token-signing-key"))
                .isEmpty();
        assertThat(environment.getProperty("memetrade.audit.retention-db.password"))
                .isEmpty();
        assertThat(environment.getProperty("ACCESS_TOKEN_SIGNING_KEY")).isNull();
        assertThat(ProcessRole.Kind.parse(environment.getProperty(ProcessRole.PROPERTY)))
                .isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(context.getBeanNamesForType(com.memetrade.identity.domain.session.AccessTokens.class))
                .isEmpty();
    }

    @Test
    void theWorkerRunsOnlyTheBotJobsAndHasNoControllerNorRequestFilter() {
        assertThat(ProcessJobs.jobs(context)).containsExactlyInAnyOrderElementsOf(WORKER_JOBS);
        assertThat(ProcessJobs.jobsWithoutRole(context)).isEmpty();
        assertThat(ProcessJobs.controllers(context)).isEmpty();
        assertThat(context.getBeanNamesForType(jakarta.servlet.Filter.class))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("authentication")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("modeguard")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("idempotencykey"));
        assertThat(environment.getProperty("memetrade.bots.scheduler.enabled")).isEqualTo("true");
    }

    @Test
    void theWorkerPoolsAreTheOnesOfTheScalabilityDesign() {
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
                // 261113-trading-real-bot U5 (U5-NFR3.7): 33 connections and 25 scheduler threads in the worker (27 and
                // 19 in the U4, plus the gates, reconciliation, kill switch, command, restore and gauges jobs).
                .isEqualTo("33");
        assertThat(environment.getProperty("spring.task.scheduling.pool.size")).isEqualTo("25");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout"))
                .isEqualTo("5000");
    }

    @Test
    void theAuthenticationContextIsTheProductionCycleContext() {
        assertThat(auth).isInstanceOf(com.memetrade.bots.infra.CycleAuthContext.class);
        assertThat(auth.optional()).isEmpty();
    }
}
