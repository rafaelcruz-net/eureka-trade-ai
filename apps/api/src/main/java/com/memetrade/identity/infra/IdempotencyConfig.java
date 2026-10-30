package com.memetrade.identity.infra;

import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.identity.domain.idempotency.IdempotencyStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdempotencyConfig {

    @Bean
    public IdempotencyService idempotencyService(
            IdempotencyStore store, Clock clock, IdempotencyProperties properties) {
        return new IdempotencyService(store, clock, properties.defaultRetention());
    }
}
