package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.MemetradeApplication;
import com.memetrade.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * CI correction (run 43769144828): the API of the REAL Compose (docker-compose.yml with infra/docker-compose.test.yml
 * and infra/docker-compose.real.yml) with production wiring only: profile {@code test}, {@code MEMETRADE_PROCESS=api},
 * the REAL on, the seed of the test release, the TxSigner URL and token, and NO test bean. The twin of
 * {@link RealWorkerWiringIT}: a wiring error of the REAL beans fails the backend job instead of the smoke.
 */
@Tag("u4-real-execution")
@SpringBootTest(
        classes = MemetradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "MEMETRADE_PROCESS=api",
            "memetrade.process=api",
            "memetrade.scheduling.enabled=false",
            "management.server.port=0",
            "memetrade.real.enabled=true",
            "MEMETRADE_REAL_SEED_TEST_RELEASE=true",
            "memetrade.real.seed-test-release=true",
            "MEMETRADE_TXSIGNER_URL=http://txsigner:8443",
            "MEMETRADE_TXSIGNER_TOKEN=wiring-test-token-not-a-secret",
            // scripts/verify.sh passes the public address of the smoke wallet; synthetic here (32 bytes of 0x07).
            "SMOKE_BETA_WALLET_ADDRESS=US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx",
            "memetrade.auth.access-token-signing-key=" + AbstractPostgresIT.TEST_SIGNING_KEY,
            "memetrade.auth.guest-app-origin=http://localhost:3000"
        })
@ActiveProfiles("test")
class RealApiWiringIT {

    static {
        AbstractPostgresIT.sharedJdbcUrl();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractPostgresIT::sharedJdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresIT.APP::user);
        registry.add("spring.datasource.password", AbstractPostgresIT.APP::password);
        registry.add("memetrade.audit.retention-db.username", AbstractPostgresIT.RETENTION::user);
        registry.add("memetrade.audit.retention-db.password", AbstractPostgresIT.RETENTION::password);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theRealApiOfTheComposeStartsWithTheRealRoutesAndThePurge() {
        assertThat(context.getBeanNamesForType(com.memetrade.realexecution.web.RealOrderController.class))
                .hasSize(1);
        assertThat(context.getBeanNamesForType(com.memetrade.realexecution.infra.RealExecutionPurgeJob.class))
                .hasSize(1);
        assertThat(context.getBeanNamesForType(com.memetrade.realexecution.infra.RealExecutionSchedule.class))
                .as("the seven jobs of the execution run only in the worker")
                .isEmpty();
        assertThat(context.getEnvironment().getProperty("memetrade.real.enabled"))
                .isEqualTo("true");
    }
}
