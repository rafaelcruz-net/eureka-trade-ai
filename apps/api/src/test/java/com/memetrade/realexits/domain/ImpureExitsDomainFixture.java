package com.memetrade.realexits.domain;

import java.sql.Connection;

/** Test-only fixture of the U7 (Step 1): a class of the domain that uses JDBC, which the purity rule refuses. */
public final class ImpureExitsDomainFixture {

    public boolean closed(Connection connection) throws java.sql.SQLException {
        return connection.isClosed();
    }
}
