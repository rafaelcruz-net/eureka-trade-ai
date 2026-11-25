package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.application.BotWalletService;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.OpenSellsFixture;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.infra.LiquidationService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 261121-paper-real-unico U4, Etapa 3 (written before the code; NFR1.4, BR2.1; performance-design.md "Travas e
 * conexões"): the read of the own holdings runs in a read-only REPEATABLE READ transaction of its own and takes no lock.
 * With the read held in the middle (S5) by a barrier, a withdrawal, a liquidation and the consumer of the queue of the
 * same person finish without waiting for it (3 of 3, whatever their business answer); inside the read the transaction
 * is {@code repeatable read} and {@code read only}, and it holds one connection of the pool.
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsIsolationIT extends AbstractRealExecutionIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BotWalletService wallets;

    @Autowired
    private LiquidationService liquidation;

    @Autowired
    private JdbcTemplate template;

    @Autowired
    private DataSource dataSource;

    @Test
    void theReadIsARepeatableReadReadOnlySnapshotThatNeverMakesTheMoneyPathsOfThePersonWait() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        new RealPnlSeed(jdbc, setup.tenantId(), setup.userId(), setup.botWalletId(), Instant.now(), 77)
                .buy(setup.botId(), setup.mint(), 1_000, 10_000, 5_000);
        new OpenSellsFixture(jdbc).pendingSell(setup.tenantId(), setup.userId(), setup.botId(), setup.mint(), 10);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> isolation = new AtomicReference<>();
        AtomicReference<String> readOnly = new AtomicReference<>();
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        RealExecutionPort real = context.getBean(RealExecutionPort.class);
        RealExecutionPort held = (RealExecutionPort) Proxy.newProxyInstance(
                RealExecutionPort.class.getClassLoader(),
                new Class<?>[] {RealExecutionPort.class},
                (proxy, m, args) -> {
                    Object answer = m.invoke(real, args);
                    if ("openSells".equals(m.getName())) {
                        isolation.set(template.queryForObject("show transaction_isolation", String.class));
                        readOnly.set(template.queryForObject("show transaction_read_only", String.class));
                        transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                        inside.countDown();
                        release.await(20, TimeUnit.SECONDS);
                    }
                    return answer;
                });

        CompletableFuture<HoldingsResult> read = CompletableFuture.supplyAsync(() -> HoldingsReaders.of(context, held)
                .holdings(setup.tenantId(), setup.userId(), setup.botId(), Duration.ofSeconds(30)));
        assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();
        int activeDuringTheRead =
                ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();

        Principal person = new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER);
        List<CompletableFuture<Void>> money = List.of(
                CompletableFuture.runAsync(() -> quietly(() ->
                        wallets.requestWithdrawal(person, new IdempotencyKey(UUID.randomUUID()), 100_000L, false))),
                CompletableFuture.runAsync(() -> quietly(() -> liquidation.start(person))),
                CompletableFuture.runAsync(() -> quietly(consumer::runOnce)));
        for (CompletableFuture<Void> path : money) {
            path.get(10, TimeUnit.SECONDS);
        }
        release.countDown();

        HoldingsResult result = read.get(20, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(HoldingsResult.Available.class);
        assertThat(isolation.get()).isEqualTo("repeatable read");
        assertThat(readOnly.get()).isEqualTo("on");
        assertThat(transactionActive.get()).isTrue();
        assertThat(activeDuringTheRead).isEqualTo(1);
        assertThat(((HoldingsResult.Available) result).holdings().getFirst().openSellBaseUnits())
                .isEqualTo(10);
    }

    /**
     * A money path may answer anything (refused, nothing to do); what counts is that it returned while the read was
     * held. Its answer is printed for the reader of the test report.
     */
    private static void quietly(Runnable path) {
        try {
            path.run();
        } catch (RuntimeException businessAnswer) {
            System.out.println(
                    "money path answered: " + businessAnswer.getClass().getSimpleName());
        }
    }
}
