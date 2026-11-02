package com.memetrade.riskassessment.infra;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.marketdata.api.MarketRiskInputs;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring of the RiskAssessment module (U4). */
@Configuration
public class RiskAssessmentConfig {

    /** Executor {@code risk-recalc}: a single thread, so recalculations run one at a time (BR3.7). */
    public static final String RECALC_EXECUTOR = "riskRecalc";

    /** Writes of the recalculation per transaction (performance-design.md). */
    public static final int BATCH_SIZE = 500;

    @Bean(RECALC_EXECUTOR)
    public ThreadPoolTaskExecutor riskRecalc() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("risk-recalc-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * The recalculation follows each collection only where the scheduled jobs run; integration tests switch the jobs
     * off ({@code memetrade.scheduling.enabled=false}) and drive the recalculation themselves.
     */
    @Bean
    public RiskRecalculator riskRecalculator(
            MarketRiskInputs market,
            JdbcRiskWeightStore weights,
            JdbcRiskAssessmentStore assessments,
            TransactionTemplate transactions,
            LockingTaskExecutor locks,
            @Qualifier(RECALC_EXECUTOR) ThreadPoolTaskExecutor executor,
            Clock clock,
            MeterRegistry meters,
            @Value("${memetrade.scheduling.enabled:true}") boolean automatic) {
        return new RiskRecalculator(
                new RiskRecalculator.Settings(
                        market, weights, assessments, transactions, locks, executor, clock, BATCH_SIZE, automatic),
                meters);
    }

    @Bean
    public RiskAssessmentService riskAssessmentService(
            MarketRiskInputs market,
            JdbcRiskWeightStore weights,
            JdbcRiskAssessmentStore assessments,
            TransactionTemplate transactions,
            Clock clock) {
        return new RiskAssessmentService(market, weights, assessments, transactions, clock);
    }

    @Bean
    public RiskWeightAdministrationService riskWeightAdministration(
            AdminAccess adminAccess,
            JdbcRiskWeightStore store,
            AuditEventPublisher audit,
            TransactionTemplate transactions,
            RiskRecalculator recalculator,
            Clock clock) {
        return new RiskWeightAdministrationService(adminAccess, store, audit, transactions, recalculator, clock);
    }

    /** Health component {@code riskRecalculation}. */
    @Bean("riskRecalculation")
    public RiskRecalculationHealth riskRecalculationHealth(RiskRecalculator recalculator, Clock clock) {
        return new RiskRecalculationHealth(recalculator, clock);
    }
}
