package com.memetrade.riskcontrol.infra;

import com.memetrade.riskcontrol.api.RejectionCounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * U8 (BR3.2): the global count of {@code order_rejection} rows evaluated since an instant. A read only, outside any
 * tenant filter BY DESIGN: the result is one number for the administrator, never a row.
 */
@Component
public class JdbcRejectionCounts implements RejectionCounts {

    private final JdbcClient jdbc;

    public JdbcRejectionCounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countRejectedSince(Instant from) {
        Objects.requireNonNull(from, "from");
        Long counted = jdbc.sql("select count(*) from order_rejection where evaluated_at >= :from")
                .param("from", Timestamp.from(from))
                .query(Long.class)
                .single();
        return counted == null ? 0 : counted;
    }
}
