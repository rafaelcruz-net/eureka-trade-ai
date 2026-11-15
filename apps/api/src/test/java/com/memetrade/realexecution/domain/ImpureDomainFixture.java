package com.memetrade.realexecution.domain;

import org.springframework.jdbc.core.simple.JdbcClient;

/** TEST ONLY: a domain class of the REAL execution that reads the database (must fail the purity rule). */
public final class ImpureDomainFixture {

    private final JdbcClient jdbc;

    ImpureDomainFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Object jdbc() {
        return jdbc;
    }
}
