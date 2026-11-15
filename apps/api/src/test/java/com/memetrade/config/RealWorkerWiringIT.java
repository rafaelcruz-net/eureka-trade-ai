package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.MemetradeApplication;
import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.SwapChain;
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
 * CI correction (run 43769144828, smoke job): the WORKER of the REAL Compose never started, because with both RPC URLs
 * set the ChainWatcher is a {@link ChainPort} AND a {@link SwapChain}, so {@code RealChainAdapter} saw two SwapChain
 * beans. Every other context test runs with test doubles or without RPC, so the wiring error only showed in
 * scripts/verify.sh.
 *
 * <p>This context is the worker of docker-compose.yml with infra/docker-compose.test.yml and
 * infra/docker-compose.real.yml: profiles {@code test,worker}, {@code MEMETRADE_PROCESS=worker}, the REAL on, the
 * TxSigner URL and token, the two RPC URLs and the recorded Jupiter, and NO test bean at all (only production wiring).
 * Scheduling stays off, so nothing calls the network: the test is about the beans, not about the jobs.
 */
@Tag("u4-real-execution")
@SpringBootTest(
        classes = MemetradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "MEMETRADE_PROCESS=worker",
            "memetrade.process=worker",
            "memetrade.scheduling.enabled=false",
            "memetrade.real.enabled=true",
            "MEMETRADE_TXSIGNER_URL=http://txsigner:8443",
            "MEMETRADE_TXSIGNER_TOKEN=wiring-test-token-not-a-secret",
            "MEMETRADE_RPC_PRIMARY_URL=http://solana-validator:8899",
            "MEMETRADE_RPC_SECONDARY_URL=http://solana-validator:8899",
            "MEMETRADE_JUPITER_BASE_URL=http://jupiter-stub:8080"
        })
@ActiveProfiles({"test", "worker"})
class RealWorkerWiringIT {

    static {
        // Starts and migrates the shared container before this context (as AbstractU5WorkerIT does).
        AbstractPostgresIT.sharedJdbcUrl();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractPostgresIT::sharedJdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresIT.APP::user);
        registry.add("spring.datasource.password", AbstractPostgresIT.APP::password);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theRealWorkerOfTheComposeStartsWithOneSwapChainAndTheRealJobs() {
        assertThat(context.getBean(RealChain.class).getClass().getSimpleName()).isEqualTo("RealChainAdapter");
        assertThat(context.getBean(ChainPort.class).getClass().getSimpleName()).isEqualTo("RpcChainWatcher");
        assertThat(context.getBeanNamesForType(com.memetrade.realexecution.infra.OrderDriver.class))
                .hasSize(1);
        assertThat(context.getBeanNamesForType(com.memetrade.botwallet.domain.DepositScanner.class))
                .hasSize(1);
        assertThat(context.getEnvironment().getProperty("memetrade.real.enabled"))
                .isEqualTo("true");
    }
}
