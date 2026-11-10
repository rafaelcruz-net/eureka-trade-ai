package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.infra.JdbcTermsAcceptanceLookup;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 4 of u3-reacceptance (F1, F2; BR3.1, BR3.4 to BR3.6; NFR2.1, NFR2.3, NFR3.2, NFR3.4, NFR3.5, NFR5.1, NFR8.1 to
 * NFR8.3; US4.1, US4.2): with the test catalog "reviewed" (legacy v1, reviewed v2 current) a person who accepted only v1
 * is locked; the acceptance of v2 records once per key and per version, two concurrent acceptances record one row, a
 * superseded version is 409, another tenant never sees nor reuses it, the table stays insert only and the lock query
 * uses the unique index. Same properties as {@code LegalDocumentsControllerIT}, so both share one Spring context.
 */
@Tag("u3-reacceptance")
@TestPropertySource(properties = "memetrade.terms.location=classpath:terms-test/reviewed/")
@ExtendWith(OutputCaptureExtension.class)
class TermsAcceptanceIT extends AbstractPostgresIT {

    private static final String ACCEPT = "/api/v1/terms/acceptances";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MeterRegistry meters;

    private AuthFlow auth;

    @BeforeEach
    void setUp() {
        auth = new AuthFlow(mvc, json);
    }

    @Test
    void anOutdatedPersonIsLockedAcceptsTheCurrentVersionAndTheSameTokenPassesRightAway() throws Exception {
        Person person = outdated();

        MockHttpServletResponse locked = get(person, "/api/v1/pnl");
        assertThat(locked.getStatus()).isEqualTo(403);
        JsonNode problem = json.readTree(locked.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
        assertThat(problem.get("currentVersion").asText()).isEqualTo("v2");

        MockHttpServletResponse accepted = accept(person, "v2", UUID.randomUUID());

        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(accepted.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode status = json.readTree(accepted.getContentAsString());
        assertThat(status.get("acceptedVersion").asText()).isEqualTo("v2");
        assertThat(status.get("currentVersion").asText()).isEqualTo("v2");
        assertThat(status.get("acceptedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(status.get("upToDate").asBoolean()).isTrue();
        assertThat(get(person, "/api/v1/pnl").getStatus()).isNotEqualTo(403);
        assertThat(rows(person, "v2")).containsExactly("REACCEPTANCE|null");
        assertThat(OutboxEvents.count(jdbc, AuditActions.TERMS_ACCEPTED, "terms:v2"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void theEventCarriesThePersonTheVersionAndTheSourceButNeverTheWalletAndTheLogOnlyTheVersion(CapturedOutput output)
            throws Exception {
        Person person = outdated();
        String correlationId = UUID.randomUUID().toString();

        mvc.perform(post(ACCEPT)
                        .header("Authorization", "Bearer " + person.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"v2\"}"))
                .andReturn();

        String event = OutboxEvents.payloads(jdbc, AuditActions.TERMS_ACCEPTED).stream()
                .filter(payload -> payload.contains(correlationId))
                .findFirst()
                .orElseThrow();
        JsonNode payload = json.readTree(event);
        assertThat(payload.get("actor").asText())
                .isEqualTo(person.seeded().userId().toString());
        assertThat(payload.get("details").get("version").asText()).isEqualTo("v2");
        assertThat(payload.get("details").get("source").asText()).isEqualTo("REACCEPTANCE");
        assertThat(event).doesNotContain(person.wallet().address()).doesNotContain("walletHmac");
        assertThat(output.getAll())
                .contains("Terms accepted: version=v2, source=REACCEPTANCE")
                .doesNotContain(person.wallet().address())
                .doesNotContain(person.token());
    }

    /** BR3.5: the same key answers the same body with a single row and a single event. */
    @Test
    void theSameKeyRecordsOnceAndAnswersTheSameBody() throws Exception {
        Person person = outdated();
        UUID key = UUID.randomUUID();

        MockHttpServletResponse first = accept(person, "v2", key);
        clock.advance(Duration.ofMinutes(2));
        MockHttpServletResponse again = accept(person, "v2", key);

        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json.readTree(again.getContentAsString())).isEqualTo(json.readTree(first.getContentAsString()));
        assertThat(rows(person, "v2")).hasSize(1);
        assertThat(eventsOf(person)).isEqualTo(1);
    }

    /** BR3.5: another key for a version already accepted answers the existing status without a new row. */
    @Test
    void anotherKeyForAnAcceptedVersionAnswersTheExistingStatus() throws Exception {
        Person person = outdated();
        String acceptedAt = json.readTree(
                        accept(person, "v2", UUID.randomUUID()).getContentAsString())
                .get("acceptedAt")
                .asText();
        // Within the 15 minutes of the access token.
        clock.advance(Duration.ofMinutes(5));

        JsonNode second = json.readTree(accept(person, "v2", UUID.randomUUID()).getContentAsString());

        assertThat(second.get("acceptedAt").asText()).isEqualTo(acceptedAt);
        assertThat(rows(person, "v2")).hasSize(1);
        assertThat(eventsOf(person)).isEqualTo(1);
    }

    /** NFR3.5: two concurrent acceptances with different keys record one row and publish one event. */
    @Test
    void twoConcurrentAcceptancesRecordOneRow() throws Exception {
        Person person = outdated();

        List<Concurrently.Outcome<MockHttpServletResponse>> outcomes = Concurrently.run(
                () -> accept(person, "v2", UUID.randomUUID()), () -> accept(person, "v2", UUID.randomUUID()));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes)
                .allSatisfy(outcome -> assertThat(outcome.value().getStatus()).isEqualTo(200));
        assertThat(json.readTree(outcomes.get(0).value().getContentAsString()))
                .isEqualTo(json.readTree(outcomes.get(1).value().getContentAsString()));
        assertThat(rows(person, "v2")).hasSize(1);
        assertThat(eventsOf(person)).isEqualTo(1);
    }

    /** BR3.6: a superseded version is 409 with the current version and records nothing. */
    @Test
    void aSupersededVersionIs409WithTheCurrentVersion() throws Exception {
        Person person = outdated();

        MockHttpServletResponse refused = accept(person, "v1", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(refused.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("TERMS_VERSION_OUTDATED");
        assertThat(problem.get("currentVersion").asText()).isEqualTo("v2");
        assertThat(problem.get("type").asText()).isEqualTo("https://memetrade.app/problems/terms");
        assertThat(rows(person, "v2")).isEmpty();
        assertThat(eventsOf(person)).isZero();
        assertThat(get(person, "/api/v1/pnl").getStatus()).isEqualTo(403);
    }

    @Test
    void anInvalidBodyOrAMissingKeyIs400AndRecordsNothing() throws Exception {
        Person person = outdated();

        for (String body : List.of("{}", "{\"version\":\"\"}", "{\"version\":\"" + "v".repeat(33) + "\"}", "[]")) {
            MockHttpServletResponse response = mvc.perform(post(ACCEPT)
                            .header("Authorization", "Bearer " + person.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn()
                    .getResponse();
            assertThat(response.getStatus()).as(body).isEqualTo(400);
            assertThat(json.readTree(response.getContentAsString()).get("code").asText())
                    .isEqualTo("VALIDATION_FAILED");
        }
        MockHttpServletResponse withoutKey = mvc.perform(post(ACCEPT)
                        .header("Authorization", "Bearer " + person.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"v2\"}"))
                .andReturn()
                .getResponse();
        assertThat(withoutKey.getStatus()).isEqualTo(400);
        assertThat(rows(person, "v2")).isEmpty();
    }

    @Test
    void withoutASessionTheAcceptanceIs401() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(ACCEPT)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"v2\"}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /**
     * Registered negative test of the CLIENT route (NFR2.1, NFR5.2): the acceptance of a person of one tenant never
     * unlocks a person of another tenant, a tenant or person in the body is refused, and a key of one person never
     * replays the answer of another.
     */
    @Test
    void theAcceptanceOfOneTenantNeverReachesAnotherTenant() throws Exception {
        Person alice = outdated();
        Person bob = outdated();
        assertThat(alice.seeded().tenantId()).isNotEqualTo(bob.seeded().tenantId());
        UUID sharedKey = UUID.randomUUID();

        accept(alice, "v2", sharedKey);
        MockHttpServletResponse withTenant = mvc.perform(post(ACCEPT)
                        .header("Authorization", "Bearer " + bob.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"v2\",\"tenantId\":\""
                                + alice.seeded().tenantId() + "\"}"))
                .andReturn()
                .getResponse();

        assertThat(withTenant.getStatus()).isEqualTo(400);
        assertThat(get(bob, "/api/v1/pnl").getStatus()).isEqualTo(403);
        assertThat(rows(bob, "v2")).isEmpty();
        assertThat(jdbc.sql("select tenant_id from terms_acceptance where user_id = :u and terms_version = 'v2'")
                        .param("u", alice.seeded().userId())
                        .query(UUID.class)
                        .single())
                .isEqualTo(alice.seeded().tenantId());

        MockHttpServletResponse bobWithAliceKey = accept(bob, "v2", sharedKey);
        assertThat(bobWithAliceKey.getStatus()).isEqualTo(200);
        assertThat(rows(bob, "v2")).hasSize(1);
        assertThat(jdbc.sql("select tenant_id from terms_acceptance where user_id = :u and terms_version = 'v2'")
                        .param("u", bob.seeded().userId())
                        .query(UUID.class)
                        .single())
                .isEqualTo(bob.seeded().tenantId());
    }

    /** NFR2.3: the application still can neither update nor delete an acceptance. */
    @Test
    void updateAndDeleteOfAnAcceptanceAreStillRefused() throws Exception {
        Person person = outdated();
        accept(person, "v2", UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("update terms_acceptance set terms_version = 'v9' where user_id = :u")
                        .param("u", person.seeded().userId())
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied for table terms_acceptance");
        assertThatThrownBy(() -> jdbc.sql("delete from terms_acceptance where user_id = :u")
                        .param("u", person.seeded().userId())
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied for table terms_acceptance");
        assertThat(rows(person, "v2")).hasSize(1);
    }

    /** NFR5.1: the cross CHECK and the unique constraint of 0013 hold in the database itself. */
    @Test
    void theConstraintsOfTheMigrationHoldInTheDatabase() {
        TestAccounts.Seeded person = TestAccounts.betaUserWithoutAcceptance(
                jdbc, TestWallet.generate().address());

        assertThatThrownBy(() -> insertRaw(person, "v5", "INVITE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("terms_acceptance_source_invite_ck");
        assertThatThrownBy(() -> insertRaw(person, "v5", "OTHER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("terms_acceptance_source_ck");
        insertRaw(person, "v5", "REACCEPTANCE");
        assertThatThrownBy(() -> insertRaw(person, "v5", "REACCEPTANCE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_terms_acceptance_user_version");
        assertThat(jdbc.sql("select column_default from information_schema.columns"
                                + " where table_name = 'terms_acceptance' and column_name = 'source'")
                        .query(String.class)
                        .single())
                .contains("INVITE");
    }

    /** NFR3.2: the query of the lock is answered through the unique constraint of (user_id, terms_version). */
    @Test
    void theLockQueryUsesTheUniqueIndex() throws Exception {
        String literal = JdbcTermsAcceptanceLookup.SQL
                .replace(":user", "'00000000-0000-4000-8000-000000000001'::uuid")
                .replace(":tenant", "'00000000-0000-4000-8000-000000000002'::uuid")
                .replace(":version", "'v2'");
        List<String> plan = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), APP.user(), APP.password());
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("set local enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("explain " + literal)) {
                    while (rows.next()) {
                        plan.add(rows.getString(1));
                    }
                }
            } finally {
                connection.rollback();
            }
        }

        assertThat(String.join("\n", plan)).contains("uq_terms_acceptance_user_version");
    }

    /** NFR8.1: the refusals and the acceptances are counted, without labels of person or tenant. */
    @Test
    void theLockAndTheAcceptanceAreCounted() throws Exception {
        double blockedBefore = counter("memetrade.terms.reacceptance.blocked", null);
        double acceptedBefore = counter("memetrade.terms.acceptances", "REACCEPTANCE");
        Person person = outdated();

        get(person, "/api/v1/bots");
        accept(person, "v2", UUID.randomUUID());
        accept(person, "v2", UUID.randomUUID());

        assertThat(counter("memetrade.terms.reacceptance.blocked", null)).isEqualTo(blockedBefore + 1);
        assertThat(counter("memetrade.terms.acceptances", "REACCEPTANCE")).isEqualTo(acceptedBefore + 1);
        assertThat(meters.get("memetrade.terms.reacceptance.check").timer().count())
                .isPositive();
        assertThat(meters.get("memetrade.terms.reacceptance.check")
                        .timer()
                        .getId()
                        .getTags())
                .isEmpty();
    }

    // ----- helpers -----

    private record Person(TestAccounts.Seeded seeded, TestWallet wallet, String token) {}

    /** A guest who accepted only v1 (the fixture default), signed in; v2 is the effective current version here. */
    private Person outdated() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        return new Person(seeded, wallet, auth.accessToken(auth.login(wallet)));
    }

    private MockHttpServletResponse accept(Person person, String version, UUID key) throws Exception {
        return mvc.perform(post(ACCEPT)
                        .header("Authorization", "Bearer " + person.token())
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"" + version + "\"}"))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse get(Person person, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + person.token()))
                .andReturn()
                .getResponse();
    }

    private List<String> rows(Person person, String version) {
        return jdbc.sql("select source, invite_id from terms_acceptance where user_id = :u and terms_version = :v")
                .param("u", person.seeded().userId())
                .param("v", version)
                .query((rs, n) -> rs.getString("source") + "|" + rs.getString("invite_id"))
                .list();
    }

    private long eventsOf(Person person) {
        return OutboxEvents.payloads(jdbc, AuditActions.TERMS_ACCEPTED).stream()
                .filter(payload -> payload.contains(person.seeded().userId().toString()))
                .count();
    }

    /** A row without an invitation (invite_id null). */
    private void insertRaw(TestAccounts.Seeded person, String version, String source) {
        jdbc.sql("""
                        insert into terms_acceptance
                            (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
                        values (:id, :user, :tenant, :version, :at, null, :source)
                        """)
                .param("id", UUID.randomUUID())
                .param("user", person.userId())
                .param("tenant", person.tenantId())
                .param("version", version)
                .param("at", Timestamp.from(clock.instant()))
                .param("source", source)
                .update();
    }

    private double counter(String name, String source) {
        var search = meters.find(name);
        if (source != null) {
            search = search.tag("source", source);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }
}
