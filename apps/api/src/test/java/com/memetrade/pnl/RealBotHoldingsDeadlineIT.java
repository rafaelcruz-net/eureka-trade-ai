package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 261121-paper-real-unico U4, Etapa 3 (written before the code; NFR1.7, BR2.2): the budget of 250 ms covers the whole
 * read. A statement that sleeps is cancelled by the {@code statement_timeout} of what remains and the read answers
 * BUDGET_EXCEEDED in at most 300 ms; the wait for a connection of an exhausted pool counts in the budget too.
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsDeadlineIT extends AbstractRealExecutionIT {

    private static final Duration BUDGET = Duration.ofMillis(250);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate template;

    @Test
    void aStatementThatSleepsIsCancelledAndTheReadIsBudgetExceededWithin300Ms() {
        Setup setup = realBot("AUTO_FULL");
        RealExecutionPort real = context.getBean(RealExecutionPort.class);
        RealExecutionPort sleepy = (RealExecutionPort) Proxy.newProxyInstance(
                RealExecutionPort.class.getClassLoader(),
                new Class<?>[] {RealExecutionPort.class},
                (proxy, m, args) -> {
                    if ("openSells".equals(m.getName())) {
                        template.execute("select pg_sleep(2)");
                    }
                    return m.invoke(real, args);
                });
        // Warm-up: the first read of the JVM loads classes; the bound is about the read, not the class loader.
        HoldingsReaders.of(context).holdings(setup.tenantId(), setup.userId(), setup.botId(), Duration.ofSeconds(5));

        long started = System.nanoTime();
        HoldingsResult result = HoldingsReaders.of(context, sleepy, 32)
                .holdings(setup.tenantId(), setup.userId(), setup.botId(), BUDGET);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED));
        assertThat(elapsed).isLessThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void theWaitForAConnectionOfAnExhaustedPoolCountsInTheBudget() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(AbstractPostgresIT.sharedJdbcUrl());
        config.setUsername(AbstractPostgresIT.APP.user());
        config.setPassword(AbstractPostgresIT.APP.password());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5_000);
        config.setPoolName("u4-deadline-test");
        try (HikariDataSource pool = new HikariDataSource(config)) {
            CountDownLatch held = new CountDownLatch(1);
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
                try (Connection connection = pool.getConnection()) {
                    held.countDown();
                    Thread.sleep(400);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            HoldingsResult result = HoldingsReaders.on(context, pool, Clock.systemUTC())
                    .holdings(setup.tenantId(), setup.userId(), setup.botId(), BUDGET);

            holder.get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED));
        }
    }
}
