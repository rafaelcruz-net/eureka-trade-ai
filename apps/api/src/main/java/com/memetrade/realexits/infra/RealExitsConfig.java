package com.memetrade.realexits.infra;

import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.realexits.domain.TriggerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the conditional exits (U7). Until the real recording of the Jupiter Trigger API (BR0) there is no real
 * adapter: outside the simulated path of the local test run, the trigger port is the unavailable one, which answers
 * every call with {@link TriggerPort.TriggerUnavailable} (503 on the routes, no change of state in the jobs). The flag
 * {@code memetrade.real.exits.enabled} stays off in every network, so no conditional is ever created there.
 */
@Configuration(proxyBeanMethods = false)
public class RealExitsConfig {

    @Bean
    @ConditionalOnMissingBean(TriggerPort.class)
    TriggerPort unavailableTriggerPort() {
        return new UnavailableTriggerPort();
    }

    /** BR0.3: the real adapter waits for the recording; every call is unavailable and nothing is sent. */
    static final class UnavailableTriggerPort implements TriggerPort {

        private static final String REASON =
                "The Jupiter Trigger adapter is built only after the real recording (BR0): nothing is sent";

        @Override
        public AdapterKind kind() {
            return AdapterKind.JUPITER;
        }

        @Override
        public Authorization authorization() {
            return Authorization.TRANSACTION;
        }

        @Override
        public String prepareCreate(CreateRequest request) {
            throw new TriggerUnavailable(REASON);
        }

        @Override
        public CreateResult create(CreateRequest request, String authorization) {
            throw new TriggerUnavailable(REASON);
        }

        @Override
        public CancelResult cancel(OrderRef order) {
            throw new TriggerUnavailable(REASON);
        }

        @Override
        public ProviderStatus status(OrderRef order) {
            throw new TriggerUnavailable(REASON);
        }
    }
}
