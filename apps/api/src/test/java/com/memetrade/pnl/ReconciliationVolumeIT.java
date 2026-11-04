package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.pnl.application.ReconciliationService;
import com.memetrade.pnl.infra.ReconciliationJob;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.U7VolumeDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR3.6 (review repair R-01 of the code generation): with the volume of NFR2.1 (20 people, 60 bots, 10 000 fills per
 * person, 3 entries per fill, about 600 000 entries) a scheduled reconciliation round finishes within 60 s and "check
 * now" of one person, through the whole filter chain, within 5 s; the data, consistent with the ledger by
 * construction, reconciles clean.
 *
 * <p>Same PostgreSQL 17 container, bootstrap and migrations as every integration test, but a database of its own
 * ({@link U7VolumeDatabase}) and therefore a Spring context of its own, so the volume never enters the rounds of the
 * other tests. The times are wall-clock times of this machine (the injectable clock of the application stays fixed);
 * the measured values are logged for docs/perf/u7-ledger-pnl-2026-11-04.md.
 */
@Tag("u7")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "memetrade.scheduling.enabled=false",
            "management.server.port=0",
            "memetrade.auth.access-token-signing-key=" + AbstractPostgresIT.TEST_SIGNING_KEY,
            "memetrade.auth.guest-app-origin=http://localhost:3000"
        })
@Import(AbstractPostgresIT.FixedClockConfig.class)
@AutoConfigureMockMvc
class ReconciliationVolumeIT {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ReconciliationVolumeIT.class);
    private static final Duration ROUND_LIMIT = Duration.ofSeconds(60);
    private static final Duration CHECK_NOW_LIMIT = Duration.ofSeconds(5);
    private static final int CHECK_NOW_PEOPLE = 5;

    private static List<U7VolumeDatabase.Person> people;

    @Autowired
    private ReconciliationJob job;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void volumeDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", U7VolumeDatabase::url);
        registry.add("spring.datasource.username", AbstractPostgresIT.APP::user);
        registry.add("spring.datasource.password", AbstractPostgresIT.APP::password);
        registry.add("memetrade.audit.retention-db.username", AbstractPostgresIT.RETENTION::user);
        registry.add("memetrade.audit.retention-db.password", AbstractPostgresIT.RETENTION::password);
    }

    @BeforeAll
    static void seedTheVolume() {
        long started = System.nanoTime();
        people = U7VolumeDatabase.seed();
        log.warn(
                "NFR3.6 volume seeded: people={}, fills={}, entries={}, positions={}, dailyRows={}, seedMs={}",
                people.size(),
                U7VolumeDatabase.count("fill"),
                U7VolumeDatabase.count("ledger_entry"),
                U7VolumeDatabase.count("position"),
                U7VolumeDatabase.count("pnl_daily"),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    @Test
    void theVolumeIsTheOneOfNfr21() {
        int expectedFills = U7VolumeDatabase.PEOPLE * U7VolumeDatabase.FILLS_PER_PERSON;
        int bots = U7VolumeDatabase.PEOPLE * U7VolumeDatabase.BOTS_PER_PERSON;

        assertThat(U7VolumeDatabase.count("fill")).isEqualTo(expectedFills);
        assertThat(U7VolumeDatabase.count("paper_order")).isEqualTo(expectedFills);
        assertThat(U7VolumeDatabase.count("ledger_entry")).isEqualTo(3L * expectedFills + bots);
        assertThat(U7VolumeDatabase.count("paper_account")).isEqualTo(bots);
        assertThat(U7VolumeDatabase.count("position")).isEqualTo((long) bots * U7VolumeDatabase.TOKENS_PER_BOT);
    }

    @Test
    void aScheduledRoundOverTheVolumeIsReconciledWithinSixtySecondsAndLogsItsDuration() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReconciliationJob.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        long started = System.nanoTime();
        ReconciliationJob.Round round;
        try {
            round = job.runOnce();
        } finally {
            logger.detachAppender(captured);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        log.warn("NFR3.6 scheduled round: people={}, elapsedMs={}", round.people(), elapsed.toMillis());

        assertThat(round.ran()).isTrue();
        assertThat(round.people()).isEqualTo(U7VolumeDatabase.PEOPLE);
        assertThat(round.failed()).isZero();
        assertThat(round.divergentPeople())
                .as("the seeded ledger reconciles clean")
                .isZero();
        assertThat(elapsed).isLessThanOrEqualTo(ROUND_LIMIT);
        assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message -> assertThat(
                        message)
                .startsWith("Reconciliation round finished: people=20, divergentPeople=0, failed=0, durationMs=")
                .doesNotContain(people.getFirst().principal().userId().toString()));
        for (U7VolumeDatabase.Person person : people) {
            assertThat(reconciliation.latest(
                            person.principal().tenantId(), person.principal().userId()))
                    .hasValueSatisfying(
                            latest -> assertThat(latest.reconciled()).isTrue());
        }
    }

    @Test
    void checkNowOfOnePersonOverTheVolumeAnswersWithinFiveSeconds() throws Exception {
        AuthFlow auth = new AuthFlow(mvc, json);
        List<Long> elapsedMs = new ArrayList<>();
        for (U7VolumeDatabase.Person person : people.subList(0, CHECK_NOW_PEOPLE)) {
            String bearer = "Bearer " + auth.accessToken(auth.login(person.wallet()));
            long started = System.nanoTime();
            MockHttpServletResponse response = mvc.perform(post("/api/v1/reconciliation/run")
                            .header("Authorization", bearer)
                            .header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andReturn()
                    .getResponse();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            elapsedMs.add(elapsed.toMillis());

            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode body = json.readTree(response.getContentAsString());
            assertThat(body.get("status").asText()).isEqualTo("RECONCILED");
            assertThat(elapsed).isLessThanOrEqualTo(CHECK_NOW_LIMIT);
        }
        log.warn("NFR3.6 check now: people={}, elapsedMs={}", elapsedMs.size(), elapsedMs);
    }
}
