package com.memetrade.audit.infra;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.domain.AuditEventReader;
import com.memetrade.audit.domain.AuditQueryService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the administrator query of the trail (plain domain class) with its JDBC reader and the publisher. */
@Configuration(proxyBeanMethods = false)
public class AuditQueryConfig {

    @Bean
    public AuditQueryService auditQueryService(AuditEventReader reader, AuditEventPublisher audit, Clock clock) {
        return new AuditQueryService(reader, audit, clock);
    }
}
