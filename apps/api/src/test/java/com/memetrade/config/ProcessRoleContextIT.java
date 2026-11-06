package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.testsupport.AbstractU5IT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Passo 1 of U5 (C20, NFR10.9, infrastructure-specification.md "Trabalhos por processo"): in the API process, the jobs
 * are exactly those of the table, every one carries {@code @ProcessRole}, the controllers exist, and none of the bot
 * jobs of the worker runs here.
 */
class ProcessRoleContextIT extends AbstractU5IT {

    /** The jobs of the api (U1 to U7 and the U5 live channel poller), found in the code at Passo 1. */
    static final java.util.Set<String> API_JOBS = java.util.Set.of(
            "AuditRelayJob",
            "AuditJobSchedule",
            "AuditStartup",
            "IdempotencyCleanupJob",
            "LoginLimiterEvictionJob",
            "MarketCollectorJob",
            "MarketHistoryCleanup",
            "ScannerRateLimitEvictionJob",
            "PendingCloseJob",
            "EquityMarkingJob",
            "ReconciliationJob",
            "ReconciliationRunLimiterEvictionJob",
            "RiskEvaluationPurgeJob",
            "DashboardMarkPoller",
            // U8: the kill switch reconciliation, its start-up round and the feedback purge (api only).
            "KillSwitchReconciler",
            "KillSwitchStartup",
            "FeedbackPurgeJob");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void theApiRunsExactlyTheJobsOfTheTableAndEachOneHasItsRole() {
        assertThat(ProcessRole.Kind.parse(environment.getProperty(ProcessRole.PROPERTY)))
                .isEqualTo(ProcessRole.Kind.API);
        // AuditStartup exists only with memetrade.scheduling.enabled (off in the tests, which drive jobs directly).
        java.util.Set<String> expected = new java.util.TreeSet<>(API_JOBS);
        expected.remove("AuditStartup");
        expected.remove("KillSwitchStartup");
        assertThat(ProcessJobs.jobs(context)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ProcessJobs.jobsWithoutRole(context)).isEmpty();
    }

    /** Whatever the profile or switch, every job class of the code carries @ProcessRole (the table of Passo 1). */
    @Test
    void everyJobClassOfTheCodeCarriesItsRole() throws Exception {
        assertThat(ProcessJobs.jobClassesWithoutRole()).isEmpty();
        assertThat(ProcessJobs.jobClasses()).containsAll(API_JOBS);
    }

    @Test
    void theApiHasTheControllersAndNoBotJobOfTheWorker() {
        assertThat(ProcessJobs.controllers(context)).contains("ScannerController", "PnlController", "BotController");
        assertThat(ProcessJobs.jobs(context))
                .doesNotContain("BotSchedules", "SlotDispatcher", "MidnightJob", "ClosingCheckJob", "DecisionPurgeJob");
        assertThat(environment.getProperty("memetrade.bots.scheduler.enabled")).isEqualTo("false");
    }

    @Test
    void theApiKeepsTheRequestAuthenticationContextAndHasNoCycleContextBean() {
        assertThat(context.getBeanNamesForType(com.memetrade.identity.web.RequestAuthContext.class))
                .hasSize(1);
        assertThat(context.getBeanNamesForType(com.memetrade.bots.infra.CycleAuthContext.class))
                .isEmpty();
        assertThat(context.getBeansOfType(AuthContext.class)).isNotEmpty();
    }
}
