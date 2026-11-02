package com.memetrade.marketdata.domain;

import java.util.function.Supplier;

/** Runs work in one database transaction: everything or nothing (F1 steps 5 to 8, NFR4.3). */
@FunctionalInterface
public interface UnitOfWork {

    <T> T inTransaction(Supplier<T> work);

    /** For in-memory tests: no transaction at all. */
    UnitOfWork DIRECT = new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
}
