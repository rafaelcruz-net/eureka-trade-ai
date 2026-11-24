package com.memetrade.realexecution;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.application.BotWalletService;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.realexecution.infra.LiquidationService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;

/**
 * 261121-paper-real-unico U4, Etapa 5 (written before the code; NFR2.2, NFR3.5; BR4.1; project.md "ALWAYS manter o
 * saque para a carteira principal registrada disponível a qualquer momento"): with the flag on and a read of the own
 * holdings that always fails like a database down, the money still leaves: the withdrawal to the main wallet, "vender
 * tudo e sacar" and the sale of the liquidation are accepted (3 of 3), and none of them reads the own holdings.
 */
@Tag("u4-own-quantity-cycle")
@TestPropertySource(properties = "memetrade.real.own-quantity-sell=true")
@Import(OwnQuantityExitsIT.FailingHoldings.class)
class OwnQuantityExitsIT extends AbstractRealExecutionIT {

    @Autowired
    private BotWalletService wallets;

    @Autowired
    private LiquidationService liquidation;

    @Autowired
    private FailingHoldings failing;

    @Test
    void theWithdrawalAndTheLiquidationNeverDependOnTheOwnHoldings() {
        Setup setup = realBot("AUTO_FULL");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        Principal person = new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER);

        IdempotentResponse withdrawal =
                wallets.requestWithdrawal(person, new IdempotencyKey(UUID.randomUUID()), 100_000_000L, false);
        UUID liquidationId = liquidation.start(person);
        liquidations.runOnce();

        assertThat(withdrawal.status()).isBetween(200, 299);
        assertThat(jdbc.sql("select state from real_liquidation where liquidation_id = :id")
                        .param("id", liquidationId)
                        .query(String.class)
                        .single())
                .isIn("SELLING", "WITHDRAWING", "DONE", "PARTIAL");
        assertThat(jdbc.sql("select count(*) from real_order where liquidation_id = :id")
                        .param("id", liquidationId)
                        .query(Long.class)
                        .single())
                .as("the sale of the liquidation was created")
                .isEqualTo(1L);
        assertThat(failing.calls.get()).isZero();
    }

    /** The read of C15 of this test: it always fails like the database down, and counts its calls. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingHoldings implements RealBotHoldings {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public com.memetrade.pnl.api.HoldingsResult holdings(
                UUID tenantId, UUID userId, UUID botId, java.time.Duration budget) {
            calls.incrementAndGet();
            throw new DataAccessResourceFailureException("the database is down (test)");
        }
    }
}
