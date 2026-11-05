package com.memetrade.testsupport;

/** Connection data of the shared test database, for the U5 tests that hold a lock on a connection of their own. */
public final class U5Sql {

    public static final String APP_USER = AbstractPostgresIT.APP.user();
    public static final String APP_PASSWORD = AbstractPostgresIT.APP.password();

    private U5Sql() {}

    public static String url() {
        return AbstractPostgresIT.POSTGRES.getJdbcUrl();
    }
}
