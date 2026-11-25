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
 * real database; optionally with another port of the orders, another clock or its own data source.
 */
public final class HoldingsReaders {

    private HoldingsReaders() {}

    /** The reader of production (S4 by id in batches, loop-back 1, F6). */
    public static RealBotHoldingsReader of(ApplicationContext context) {
        return of(context, context.getBean(RealExecutionPort.class));
    }

    /**
     * The reader of production with another clock: the measurement uses the clock of the system, so
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
                clock);
    }

    /** The reader with another port of the execution (a double that wraps the real one). */
    public static RealBotHoldingsReader of(ApplicationContext context, RealExecutionPort orders) {
        return new RealBotHoldingsReader(
                context.getBean(RealLedger.class),
                orders,
                context.getBean(BotDirectory.class),
                context.getBean(RealStateQuery.class),
                context.getBean(RealCostMethod.class),
                context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(Clock.class));
    }

    /**
     * Etapa 32 (loop-back 1, F6): the reader of production whose transaction AND port of the orders run on the data
     * source of the test recorded by {@code log}, so the statements of S4 against {@code real_order} can be counted.
     * The ledger, the directory of bots and the bot wallets stay on the beans of the context (they are not counted).
     */
    public static RealBotHoldingsReader counted(ApplicationContext context, StatementLog log) {
        DataSource recorded = log.wrap(context.getBean(DataSource.class));
        com.memetrade.realexecution.infra.JdbcRealExecutionStore store =
                new com.memetrade.realexecution.infra.JdbcRealExecutionStore(
                        org.springframework.jdbc.core.simple.JdbcClient.create(recorded), recorded);
        RealExecutionPort orders = new com.memetrade.realexecution.infra.RealExecutionService(
                store,
                context.getBean(com.memetrade.realexecution.infra.RealOrderLedger.class),
                context.getBean(com.memetrade.realexecution.infra.RealExecutionEvents.class),
                context.getBean(com.memetrade.realexecution.infra.OrderTransitions.class),
                context.getBean(Clock.class));
        return new RealBotHoldingsReader(
                context.getBean(RealLedger.class),
                orders,
                context.getBean(BotDirectory.class),
                context.getBean(RealStateQuery.class),
                context.getBean(RealCostMethod.class),
                new JdbcTemplate(recorded),
                new DataSourceTransactionManager(recorded),
                context.getBean(Clock.class));
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
                clock);
    }
}
