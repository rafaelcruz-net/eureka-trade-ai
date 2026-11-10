package com.memetrade.privacy.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingJdbc;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 11 of u4-data-requests: the synthetic requests of the load scenario are written only when the seed is above 0,
 * never twice, and only into the table of the Privacy module (never a table of Identity).
 */
@Tag("u4-data-requests")
class DataRequestLoadSeedTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));

    @Test
    void aSeedOfZeroWritesNothing() {
        RecordingJdbc jdbc = new RecordingJdbc(0L);

        assertThat(new DataRequestLoadSeed(jdbc.client(), clock, 0).seed()).isZero();
        assertThat(jdbc.statements()).isEmpty();
    }

    @Test
    void onlyTheMissingRequestsAreWrittenAndOnlyInThePrivacyTable() {
        RecordingJdbc jdbc = new RecordingJdbc(1L);
        DataRequestLoadSeed seeder = new DataRequestLoadSeed(jdbc.client(), clock, 4);

        seeder.onReady();

        assertThat(jdbc.statements()).hasSize(4);
        assertThat(jdbc.statements().getFirst()).startsWith("select count(*) from data_subject_request");
        assertThat(jdbc.statements().subList(1, 4))
                .allSatisfy(sql -> assertThat(sql).startsWith("insert into data_subject_request"))
                .noneSatisfy(sql -> assertThat(sql).contains("app_user"));
        assertThat(jdbc.parameters()).containsEntry("contact", "carga-2@example.invalid");
    }

    @Test
    void aNegativeSeedIsRefused() {
        assertThatThrownBy(() -> new DataRequestLoadSeed(new RecordingJdbc(0L).client(), clock, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
