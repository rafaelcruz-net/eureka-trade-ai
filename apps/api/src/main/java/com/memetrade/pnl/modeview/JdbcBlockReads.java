package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.shared.config.ProcessRole;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U1: the transactions of the blocks of the P&L by mode on PostgreSQL. Each block opens its own
 * read-only transaction ({@code REQUIRES_NEW}: a thread of the pool never joins another one) and sets
 * {@code set local statement_timeout} to what is left of its deadline. No lock.
 *
 * <p>261121-paper-real-unico U2 (performance-design.md "Transação"): both blocks read in REPEATABLE READ. The REAL block
 * now reads the settled orders and then the ledger rows, and its exact reconciliation (BR8.1) needs both reads to see
 * the same snapshot.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class JdbcBlockReads implements BlockReads {

    private final JdbcClient jdbc;
    private final TransactionTemplate paper;
    private final TransactionTemplate real;

    public JdbcBlockReads(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.paper = template(transactions, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.real = template(transactions, TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public <T> T inTransaction(PnlMarket market, Callable<T> body) throws Exception {
        TransactionTemplate template = market == PnlMarket.PAPER ? paper : real;
        try {
            return template.execute(status -> {
                try {
                    return body.call();
                } catch (RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new BlockFailure(failure);
                }
            });
        } catch (BlockFailure wrapped) {
            throw (Exception) wrapped.getCause();
        }
    }

    @Override
    public void statementTimeout(Duration left) {
        long millis = Math.max(1, left.toMillis());
        jdbc.sql("set local statement_timeout = '" + millis + "ms'").update();
    }

    private static TransactionTemplate template(PlatformTransactionManager transactions, int isolation) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        template.setIsolationLevel(isolation);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** A checked failure of a block carried through the transaction template and unwrapped after the rollback. */
    private static final class BlockFailure extends RuntimeException {
        BlockFailure(Exception cause) {
            super(cause);
        }
    }
}
