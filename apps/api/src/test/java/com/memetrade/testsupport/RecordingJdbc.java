package com.memetrade.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * u4-data-requests (Passo 2): a {@link JdbcClient} for unit tests of a single-count adapter (J2 to J4). It records the
 * SQL and the named parameters of every statement and answers {@code query(Long.class).single()} with a fixed value, so
 * the test proves the filters an adapter binds without a database. The real SQL runs in the integration tests.
 */
public final class RecordingJdbc {

    private final JdbcClient client = mock(JdbcClient.class);
    private final List<String> statements = new ArrayList<>();
    private final Map<String, Object> parameters = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public RecordingJdbc(Long countAnswer) {
        StatementSpec spec = mock(StatementSpec.class);
        MappedQuerySpec<Long> mapped = mock(MappedQuerySpec.class);
        when(client.sql(anyString())).thenAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            return spec;
        });
        when(spec.param(anyString(), any())).thenAnswer(invocation -> {
            parameters.put(invocation.getArgument(0), invocation.getArgument(1));
            return spec;
        });
        when(spec.query(Long.class)).thenReturn(mapped);
        when(mapped.single()).thenReturn(countAnswer);
    }

    public JdbcClient client() {
        return client;
    }

    /** The SQL of every statement, in order, with its white space collapsed. */
    public List<String> statements() {
        return statements.stream()
                .map(sql -> sql.replaceAll("\\s+", " ").trim())
                .toList();
    }

    /** The last value bound to each name (null values included). */
    public Map<String, Object> parameters() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
