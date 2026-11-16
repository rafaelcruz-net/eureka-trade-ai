package com.memetrade.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 261113-trading-real-bot U4: the {@link RecordedSwapFixture} of the REAL Compose, ONLY in the test profile and only
 * when {@code memetrade.real.test-swap.mint} ({@code RECORDED_MINT}, declared in {@code application-test.yml} alone) is
 * set. Without the recordings the variable is empty and no bean exists, so the api and the worker behave exactly as
 * before. Exists in both processes: the api pins the simulated tokens, the worker runs the barrier and the strategy.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
@ConditionalOnExpression("'${memetrade.real.test-swap.mint:}' != ''")
public class RecordedSwapFixtureConfig {

    private static final Logger log = LoggerFactory.getLogger(RecordedSwapFixtureConfig.class);

    @Bean
    public RecordedSwapFixture recordedSwapFixture(
            @Value("${memetrade.real.test-swap.mint}") String mint,
            @Value("${memetrade.real.test-swap.decimals}") int decimals,
            @Value("${memetrade.real.test-swap.buy-lamports}") long buyLamports,
            @Value("${memetrade.real.test-swap.expected-out-units}") long expectedOutUnits) {
        RecordedSwapFixture fixture = new RecordedSwapFixture(mint.trim(), decimals, buyLamports, expectedOutUnits);
        if (fixture.tokenUsd().signum() <= 0) {
            throw new IllegalArgumentException(
                    "RECORDED_SELL_UNITS is out of range: the recorded price of the mint rounds to zero");
        }
        log.warn(
                "TEST PROFILE ACTIVE: recorded swap fixture loaded (buyLamports={}); sim-a publishes the recorded mint"
                        + " and the REAL barrier treats the source as REAL for that mint only",
                buyLamports);
        return fixture;
    }
}
