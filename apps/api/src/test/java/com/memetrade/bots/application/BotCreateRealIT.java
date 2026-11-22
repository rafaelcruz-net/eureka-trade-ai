package com.memetrade.bots.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.OutboxEvents;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1, Step 5, WRITTEN BEFORE THE CODE (BR1.1 to BR1.4, BR2.3 to BR2.5, NFR1.12, NFR3.5, NFR4.5,
 * NFR4.9; AC1.3.x, AC1.4.x, AC1.5.x): the REAL creation inside the idempotent command, through the whole filter chain.
 *
 * <ul>
 *   <li>PAPER creates as today and never reads the eligibility;
 *   <li>REAL eligible creates the draft with {@code mode == market == REAL};
 *   <li>each reason answers {@code 409 REAL_NOT_ELIGIBLE} with it, one audit event that survives the rollback, no bot,
 *       no idempotency record and one more count of the metric;
 *   <li>an unavailable source answers 503 (never 500, even with a real {@code statement_timeout} of the database);
 *   <li>a refusal and a 503 are never stored: the same key evaluates everything again; a completed creation repeated
 *       with the same key answers the stored response without evaluating again.
 * </ul>
 *
 * REAL_DISABLED is covered by {@code BotRoutesIT}, whose context has the REAL switched off (the default).
 */
class BotCreateRealIT extends AbstractPaperRealIT {

    @Test
    void paperCreatesAsTodayAndNeverReadsTheEligibilityEvenWithTheSourceDown() throws Exception {
        Person person = signIn(false, false);
        faultyState.fault.set(Fault.FAIL);
        killSwitch(true);

        MockHttpServletResponse explicit = create(person, "PAPER", UUID.randomUUID());
        MockHttpServletResponse absent = create(person, null, UUID.randomUUID());

        assertThat(explicit.getStatus()).as(explicit.getContentAsString()).isEqualTo(201);
        assertThat(absent.getStatus()).isEqualTo(201);
        JsonNode bot = body(absent);
        assertThat(bot.get("market").asText()).isEqualTo("PAPER");
        assertThat(bot.get("mode").asText()).isEqualTo("PAPER");
        assertThat(bots(person.userId())).isEqualTo(2);
    }

    @Test
    void anEligiblePersonCreatesARealDraftWithModeEqualToMarket() throws Exception {
        Person person = eligiblePerson();

        MockHttpServletResponse created = create(person, "REAL", UUID.randomUUID());

        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode bot = body(created);
        assertThat(bot.get("state").asText()).isEqualTo("DRAFT");
        assertThat(bot.get("market").asText()).isEqualTo("REAL");
        assertThat(bot.get("mode").asText()).isEqualTo("REAL");
        assertThat(OutboxEvents.count(
                        jdbc, "BOT_CREATED", "bot:" + bot.get("botId").asText()))
                .isEqualTo(1);
    }

    @Test
    void anUnknownMarketIs422OnTheFieldAndNothingIsCreated() throws Exception {
        Person person = eligiblePerson();

        MockHttpServletResponse refused = create(person, "BOTH", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentAsString()).contains("market");
        assertThat(bots(person.userId())).isZero();
    }

    @Test
    void theKillSwitchIsRefusedWith409AuditedAndCountedAndNothingIsKept() throws Exception {
        Person person = eligiblePerson();
        killSwitch(true);
        assertRefused(person, "KILL_SWITCH");
    }

    @Test
    void anInactiveWalletIsNow409AndNever422() throws Exception {
        Person person = signIn(true, true);
        assertRefused(person, "WALLET_INACTIVE");
    }

    @Test
    void withoutAReleaseTheReasonIsNotReleased() throws Exception {
        Person person = signIn(true, false);
        activeWallet(person, 5 * SOL);
        assertRefused(person, "NOT_RELEASED");
    }

    @Test
    void aReleaseInQuarantineIsRefusedWithQuarantine() throws Exception {
        Person person = signIn(true, false);
        activeWallet(person, 5 * SOL);
        quarantinedRelease(person.userId());
        assertRefused(person, "QUARANTINE");
    }

    @Test
    void aReasonAlreadyDecidedWinsOverASourceThatWouldFail() throws Exception {
        Person person = eligiblePerson();
        killSwitch(true);
        faultyState.fault.set(Fault.FAIL);

        MockHttpServletResponse refused = create(person, "REAL", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(body(refused).get("reason").asText()).isEqualTo("KILL_SWITCH");
    }

    @Test
    void aSourceThatFailsAnswers503WithNothingWrittenAndTheRetryWithTheSameKeyCreates() throws Exception {
        Person person = eligiblePerson();
        UUID key = UUID.randomUUID();
        faultyState.fault.set(Fault.FAIL);

        MockHttpServletResponse unavailable = create(person, "REAL", key);

        assertThat(unavailable.getStatus()).isEqualTo(503);
        JsonNode problem = body(unavailable);
        assertThat(problem.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(problem.get("correlationId").asText()).isNotBlank();
        assertThat(bots(person.userId())).isZero();
        assertThat(idempotencyRecords(key)).isZero();

        faultyState.fault.set(Fault.NONE);
        MockHttpServletResponse retried = create(person, "REAL", key);

        assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(201);
        assertThat(bots(person.userId())).isEqualTo(1);
    }

    /** reliability-design.md: a real statement_timeout (57014) inside the savepoint is 503, never 500 (no 25P02). */
    @Test
    void aRealStatementTimeoutOfTheDatabaseIs503AndNever500() throws Exception {
        Person person = eligiblePerson();
        UUID key = UUID.randomUUID();
        faultyState.fault.set(Fault.SLEEP);

        MockHttpServletResponse unavailable = create(person, "REAL", key);

        assertThat(unavailable.getStatus()).as(unavailable.getContentAsString()).isEqualTo(503);
        assertThat(bots(person.userId())).isZero();
        assertThat(idempotencyRecords(key)).isZero();
    }

    @Test
    void aRefusalIsNotStoredSoTheSameKeyEvaluatesAgainAndCreates() throws Exception {
        Person person = eligiblePerson();
        UUID key = UUID.randomUUID();
        killSwitch(true);

        assertThat(create(person, "REAL", key).getStatus()).isEqualTo(409);
        killSwitch(false);
        MockHttpServletResponse retried = create(person, "REAL", key);

        assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(201);
        assertThat(bots(person.userId())).isEqualTo(1);
    }

    @Test
    void aCompletedCreationRepeatedWithTheSameKeyAnswersTheStoredResponseWithoutEvaluatingAgain() throws Exception {
        Person person = eligiblePerson();
        UUID key = UUID.randomUUID();
        MockHttpServletResponse first = create(person, "REAL", key);
        killSwitch(true);

        MockHttpServletResponse repeated = create(person, "REAL", key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(repeated.getStatus()).isEqualTo(201);
        assertThat(body(repeated).get("botId").asText())
                .isEqualTo(body(first).get("botId").asText());
        assertThat(bots(person.userId())).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherBodyIsAnIdempotencyConflict() throws Exception {
        Person person = eligiblePerson();
        UUID key = UUID.randomUUID();
        assertThat(create(person, "REAL", key).getStatus()).isEqualTo(201);

        MockHttpServletResponse other = create(person, "PAPER", key);

        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(body(other).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(bots(person.userId())).isEqualTo(1);
    }

    /** security-design.md item 7: a refusal never leaves without its record; a failed audit write is 503. */
    @Test
    void aFailedAuditOfTheRefusalIs503AndTheMetricDoesNotCount() throws Exception {
        Person person = eligiblePerson();
        killSwitch(true);
        faultyAudit.failNext.set(true);
        double before = refusals("KILL_SWITCH");

        MockHttpServletResponse unavailable = create(person, "REAL", UUID.randomUUID());

        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(refusals("KILL_SWITCH")).isEqualTo(before);
        assertThat(bots(person.userId())).isZero();
    }

    @Test
    void theRefusalNeverCarriesTheTokenNorTheWalletAddress() throws Exception {
        Person person = signIn(true, true);

        MockHttpServletResponse refused = create(person, "REAL", UUID.randomUUID());

        assertThat(refused.getContentAsString())
                .doesNotContain(person.wallet().address())
                .doesNotContain(person.bearer().substring("Bearer ".length()));
    }

    private void assertRefused(Person person, String reason) throws Exception {
        UUID key = UUID.randomUUID();
        double before = refusals(reason);
        Instant start = clock.instant();

        MockHttpServletResponse refused = create(person, "REAL", key);

        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(409);
        JsonNode problem = body(refused);
        assertThat(problem.get("code").asText()).isEqualTo("REAL_NOT_ELIGIBLE");
        assertThat(problem.get("reason").asText()).isEqualTo(reason);
        String correlationId = problem.get("correlationId").asText();
        assertThat(correlationId).isNotBlank();
        assertThat(bots(person.userId())).isZero();
        assertThat(idempotencyRecords(key)).isZero();
        assertThat(refusals(reason)).isEqualTo(before + 1);
        List<String> audits = OutboxEvents.payloads(jdbc, "BOT_REAL_CREATION_REFUSED");
        assertThat(audits.stream().filter(p -> p.contains(correlationId)))
                .singleElement()
                .satisfies(payload -> assertThat(payload).contains(reason).contains("bot-creation"));
        assertThat(clock.instant()).isEqualTo(start);
    }
}
