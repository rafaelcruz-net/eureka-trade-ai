package com.memetrade.pnl.application;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;

/**
 * 261121-paper-real-unico U4, Etapa 32 (test support; loop-back 1, decision F6): a {@link DataSource} that records the
 * SQL of every statement prepared on its connections, so a test counts the statements of S4 against
 * {@code real_order} instead of trusting the code. Everything else goes to the real data source unchanged.
 */
public final class StatementLog {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    /** The SQL prepared so far, in order. */
    public List<String> statements() {
        return List.copyOf(statements);
    }

    /** Forgets what was recorded. */
    public void clear() {
        statements.clear();
    }

    /** How many recorded statements contain every one of {@code fragments} (case-insensitive). */
    public long count(String... fragments) {
        return statements.stream()
                .map(sql -> sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "))
                .filter(sql -> java.util.Arrays.stream(fragments)
                        .allMatch(fragment -> sql.contains(fragment.toLowerCase(java.util.Locale.ROOT))))
                .count();
    }

    /** {@code real} with every prepared statement recorded. */
    public DataSource wrap(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    return result instanceof Connection connection ? connection(connection) : result;
                });
    }

    private Connection connection(Connection real) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql) {
                statements.add(sql);
            }
            return invoke(real, method, args);
        };
        return (Connection)
                Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
