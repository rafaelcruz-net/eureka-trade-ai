package com.memetrade.realexecution.infra.jupiter;

import com.memetrade.realexecution.domain.SwapRouter;
import com.memetrade.realexecution.infra.RealExecutionConfig;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Jupiter client of the worker (X1), from the checked settings of the REAL execution. */
@Configuration(proxyBeanMethods = false)
public class JupiterConfig {

    @Bean
    public SwapRouter swapRouter(RealExecutionConfig.CheckedSettings settings) {
        var jupiter = settings.properties().jupiter();
        return new JupiterClient(
                HttpClient.newBuilder().connectTimeout(jupiter.timeout()).build(),
                jupiter.baseUrl(),
                jupiter.timeout(),
                settings.properties().maxPriorityFeeLamports());
    }
}
