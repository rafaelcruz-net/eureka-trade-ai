package com.memetrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPostgresIT;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApplicationContextIT extends AbstractPostgresIT {

    @Autowired
    private Clock injectedClock;

    @Test
    void contextStartsWithMigrationsAppliedAndInjectableClock() {
        Integer changeSets = jdbc.sql("select count(*) from databasechangelog")
                .query(Integer.class)
                .single();
        assertThat(changeSets).isPositive();
        assertThat(injectedClock).isSameAs(clock);
        assertThat(injectedClock.getZone().getId()).isEqualTo("Z");
    }
}
