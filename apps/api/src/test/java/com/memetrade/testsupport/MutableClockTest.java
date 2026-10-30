package com.memetrade.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class MutableClockTest {

    @Test
    void startsAtFixedUtcInstantAndAdvancesOnlyWhenTold() {
        MutableClock clock = new MutableClock();
        assertThat(clock.instant()).isEqualTo(MutableClock.DEFAULT_START);
        assertThat(clock.getZone().getId()).isEqualTo("Z");

        clock.advance(Duration.ofMinutes(5));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-28T12:05:00Z"));

        clock.set(Instant.EPOCH);
        assertThat(clock.instant()).isEqualTo(Instant.EPOCH);
    }
}
