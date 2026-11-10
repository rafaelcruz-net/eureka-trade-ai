package com.memetrade.privacy.loadtest;

import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.shared.config.ProcessRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * u4-data-requests (Passo 11; infrastructure-specification.md "Dados de teste"): the synthetic data subject requests of
 * the load scenario {@code data-requests}, only with the {@code loadtest} profile and only when
 * {@code memetrade.loadtest.data-requests.seed} is above 0 (the default 0 seeds nothing). Half DONE (closed, out of the
 * unique index of the open requests) and half RECEIVED, rotating the four types, with synthetic texts and contacts of
 * the reserved domain {@code example.invalid}.
 *
 * <p>Named like {@code LoadtestLedgerSeed}: the classes named {@code *Seeder} are the test-profile seeders of U3
 * (U3ArchitectureTest), and this one belongs to the {@code loadtest} profile.
 *
 * <p>Deviation from the design (recorded in the report of the Unit): the rows are EMAIL requests WITHOUT a linked
 * account, so this class never writes a table of Identity (team.md: a module never reads or writes the tables of
 * another); the administrator list, the status index and the due-date index are loaded exactly as with accounts. A
 * second start does not seed twice.
 */
@Component
@Profile("loadtest")
@ProcessRole(ProcessRole.Kind.API)
public class DataRequestLoadSeed {

    static final String CONTACT_DOMAIN = "@example.invalid";

    private static final Logger log = LoggerFactory.getLogger(DataRequestLoadSeed.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int seed;

    public DataRequestLoadSeed(
            JdbcClient jdbc, Clock clock, @Value("${memetrade.loadtest.data-requests.seed:0}") int seed) {
        this.jdbc = jdbc;
        this.clock = clock;
        if (seed < 0) {
            throw new IllegalArgumentException("memetrade.loadtest.data-requests.seed must not be negative");
        }
        this.seed = seed;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        seed();
    }

    /** Seeds the requests still missing, one auto-committed insert each; returns how many were written. */
    public int seed() {
        if (seed == 0) {
            return 0;
        }
        Long existing = jdbc.sql("select count(*) from data_subject_request where contact like :pattern")
                .param("pattern", "carga-%" + CONTACT_DOMAIN)
                .query(Long.class)
                .single();
        int missing = (int) Math.max(0, seed - (existing == null ? 0 : existing));
        Instant now = clock.instant();
        for (int index = 0; index < missing; index++) {
            insert(index, now);
        }
        log.info("Data request load seed: written={}, alreadyPresent={}", missing, existing);
        return missing;
    }

    private void insert(int index, Instant now) {
        boolean closed = index % 2 == 0;
        Instant receivedAt = now.minus(Duration.ofHours(1L + index % 300));
        jdbc.sql("""
                        insert into data_subject_request
                          (request_id, type, origin, details, contact, status, received_at, due_at, response,
                           closed_at)
                        values (:id, :type, 'EMAIL', :details, :contact, :status, :receivedAt, :dueAt, :response,
                                :closedAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("type", DataRequestType.values()[index % DataRequestType.values().length].name())
                .param("details", "Pedido sintético de carga " + index)
                .param("contact", "carga-" + index + CONTACT_DOMAIN)
                .param("status", closed ? "DONE" : "RECEIVED")
                .param("receivedAt", Timestamp.from(receivedAt))
                .param("dueAt", Timestamp.from(receivedAt.plus(Duration.ofDays(15))))
                .param("response", closed ? "Resposta sintética." : null)
                .param("closedAt", closed ? Timestamp.from(now) : null)
                .update();
    }
}
