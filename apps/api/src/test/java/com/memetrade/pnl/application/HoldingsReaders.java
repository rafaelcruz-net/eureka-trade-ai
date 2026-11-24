package com.memetrade.pnl.application;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.RealCostMethod;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.realexecution.api.RealExecutionPort;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U4 (test support of the Etapas 3 to 5): the {@link RealBotHoldingsReader} of the worker built
 * from the beans of an api test context (the reader is a worker bean), with the real ports, the real ledger and the
 * real database; optionally with another threshold of the read of the orders by id, or with its own data source.
 */
public final class HoldingsReaders {

    private HoldingsReaders() {}

    /** The reader with the threshold of production (32). */
    public static RealBotHoldingsReader of(ApplicationContext context) {
        return of(context, RealBotHoldingsReader.ORDER_PREFETCH_THRESHOLD);
    }

    /** The reader with another threshold: above it the orders of the lots are streamed, at or below read by id. */
    public static RealBotHoldingsReader of(ApplicationContext context, int threshold) {
        return of(context, context.getBean(RealExecutionPort.class), threshold);
    }

    /**
     * The reader with the threshold of production and another clock: the measurement uses the clock of the system, so
     * every layer of the {@code ReadDeadline} (the statement timeout of what remains, the checks, the check after the
     * commit) acts as in production (review R-01).
     */
    public static RealBotHoldingsReader withClock(ApplicationContext context, Clock clock) {
        return new RealBotHoldingsReader(
                context.getBean(RealLedger.class),
                context.getBean(RealExecutionPort.class),
                context.getBean(BotDirectory.class),
                context.getBean(RealStateQuery.class),
                context.getBean(RealCostMethod.class),
                context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class),
                clock,
                RealBotHoldingsReader.ORDER_PREFETCH_THRESHOLD);
    }

    /** The reader with another port of the execution (a double that wraps the real one). */
    public static RealBotHoldingsReader of(ApplicationContext context, RealExecutionPort orders, int threshold) {
        return new RealBotHoldingsReader(
                context.getBean(RealLedger.class),
                orders,
                context.getBean(BotDirectory.class),
                context.getBean(RealStateQuery.class),
                context.getBean(RealCostMethod.class),
                context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(Clock.class),
                threshold);
    }

    /** The reader on a data source of its own (a pool the test can exhaust), with the clock of {@code clock}. */
    public static RealBotHoldingsReader on(ApplicationContext context, DataSource dataSource, Clock clock) {
        return new RealBotHoldingsReader(
                context.getBean(RealLedger.class),
                context.getBean(RealExecutionPort.class),
                context.getBean(BotDirectory.class),
                context.getBean(RealStateQuery.class),
                context.getBean(RealCostMethod.class),
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                clock,
                RealBotHoldingsReader.ORDER_PREFETCH_THRESHOLD);
    }
}
