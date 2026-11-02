package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.domain.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link UnitOfWork} on the main pool: one Spring transaction, rolled back on any exception. */
public class TransactionUnitOfWork implements UnitOfWork {

    private final TransactionTemplate transactions;

    public TransactionUnitOfWork(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
