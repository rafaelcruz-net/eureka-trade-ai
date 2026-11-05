package com.memetrade.bots.infra;

import com.memetrade.bots.domain.BotConfigValidator;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.shared.config.ProcessRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires the pure classes of U5 (validator, decision engine, decision record) and the cycle executor of the worker. */
@Configuration(proxyBeanMethods = false)
public class BotsConfig {

    /** The name of the executor of the cycles (worker only, NFR2.4). */
    public static final String CYCLE_EXECUTOR = "botCycleExecutor";

    @Bean
    public BotConfigValidator botConfigValidator(BotProperties properties) {
        return new BotConfigValidator(
                properties.virtualBalance().min(), properties.virtualBalance().cap());
    }

    @Bean
    public DecisionEngine decisionEngine() {
        return new DecisionEngine();
    }

    @Bean
    public DecisionJson decisionJson() {
        return new DecisionJson();
    }

    /** NFR2.4: {@code cycle-threads} (4) cycles at the same time; the queue holds the ACTIVE bots of the slot. */
    @Bean(CYCLE_EXECUTOR)
    @ProcessRole(ProcessRole.Kind.WORKER)
    public ThreadPoolTaskExecutor botCycleExecutor(BotProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.cycleThreads());
        executor.setMaxPoolSize(properties.cycleThreads());
        executor.setThreadNamePrefix("bot-cycle-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
