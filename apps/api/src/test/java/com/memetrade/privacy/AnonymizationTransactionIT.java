package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import com.memetrade.privacy.domain.anonymization.AnonymizationService;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (BR5.2, BR5.3; AC6.2.1, AC6.1.5): on the real database, one
 * anonymization takes the account to ANONYMIZED, removes the address and the provider of the wallet with
 * {@code anonymized_at}, revokes every session, deletes the login challenges of the address, erases the comments of the
 * person only, stops and renames the bots, publishes ACCOUNT_ANONYMIZED with the counts only and concludes the request
 * with the model response and DATA_REQUEST_TRANSITIONED.
 */
class AnonymizationTransactionIT extends AbstractAnonymizationIT {

    @Test
    void theAccountTheWalletTheSessionsAndTheChallengesAreAnonymizedTogether() throws Exception {
        Deletion deletion = deletionInProgress();
        seedSessions(deletion.guest().principal(), 2);
        assertThat(challengesOf(deletion.wallet())).isPositive();

        AnonymizationResult result = anonymize(deletion);

        assertThat(accountStatus(deletion.userId())).isEqualTo("ANONYMIZED");
        Map<String, Object> wallet = walletRow(deletion.userId());
        assertThat(wallet.get("address")).isNull();
        assertThat(wallet.get("provider")).isNull();
        assertThat(((Timestamp) wallet.get("anonymized_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(sessions(deletion.userId())).isEqualTo(3);
        assertThat(activeSessions(deletion.userId())).isZero();
        assertThat(challengesOf(deletion.wallet())).isZero();
        assertThat(result.sessionsRevoked()).isEqualTo(3);
        assertThat(result.anonymizedAt()).isEqualTo(clock.instant());
        assertThat(result.alreadyAnonymized()).isFalse();
    }

    @Test
    void theStoredAnswerOfTheLoginChallengeOfTheAddressIsDeletedToo() throws Exception {
        Deletion deletion = deletionInProgress();
        long before = jdbc.sql("select count(*) from idempotency_record where scope like :s")
                .param("s", "wallet:" + deletion.wallet() + ":%")
                .query(Long.class)
                .single();
        assertThat(before)
                .as("the login of the fixture stored its challenge answer")
                .isPositive();

        anonymize(deletion);

        assertThat(jdbc.sql("select count(*) from idempotency_record where scope like :s")
                        .param("s", "wallet:" + deletion.wallet() + ":%")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theBotsStopWithoutClosingTheDraftsStayAndEveryBotIsRenamed() throws Exception {
        Deletion deletion = deletionInProgress();
        UUID active = seedBot(deletion.guest().principal(), "ACTIVE");
        UUID paused = seedBot(deletion.guest().principal(), "PAUSED");
        UUID closing = seedBot(deletion.guest().principal(), "CLOSING_PENDING");
        UUID draft = seedBot(deletion.guest().principal(), "DRAFT");
        UUID validated = seedBot(deletion.guest().principal(), "VALIDATED");
        UUID stopped = seedBot(deletion.guest().principal(), "STOPPED");

        AnonymizationResult result = anonymize(deletion);

        assertThat(result.botsStopped()).isEqualTo(3);
        assertThat(botState(active)).isEqualTo("STOPPED");
        assertThat(botState(paused)).isEqualTo("STOPPED");
        assertThat(botState(closing)).isEqualTo("STOPPED");
        assertThat(botState(draft)).isEqualTo("DRAFT");
        assertThat(botState(validated)).isEqualTo("VALIDATED");
        assertThat(botState(stopped)).isEqualTo("STOPPED");
        assertThat(bots(deletion.userId())).hasSize(6).allSatisfy(bot -> {
            assertThat(bot.get("name")).isEqualTo(BotOwnerDataPort.ANONYMIZED_BOT_NAME);
            assertThat(bot.get("pause_reasons")).isEqualTo("");
        });
    }

    @Test
    void onlyTheCommentsOfThePersonAreErased() throws Exception {
        Deletion deletion = deletionInProgress();
        WalletGuest other = newWalletGuest();
        seedFeedback(deletion.guest().principal(), 3);
        seedFeedback(other.principal(), 2);

        AnonymizationResult result = anonymize(deletion);

        assertThat(result.feedbackDeleted()).isEqualTo(3);
        assertThat(feedbackOf(deletion.userId())).isZero();
        assertThat(feedbackOf(other.principal().userId())).isEqualTo(2);
    }

    @Test
    void theRequestIsDoneWithTheModelResponseAndTheTwoEventsHaveOnlyIdentifiersAndCounts() throws Exception {
        Deletion deletion = deletionInProgress();
        seedBot(deletion.guest().principal(), "ACTIVE");
        seedFeedback(deletion.guest().principal(), 1);

        AnonymizationResult result = anonymize(deletion);

        assertThat(status(deletion.requestId())).isEqualTo("DONE");
        assertThat(jdbc.sql("select response from data_subject_request where request_id = :id")
                        .param("id", UUID.fromString(deletion.requestId()))
                        .query(String.class)
                        .single())
                .isEqualTo(AnonymizationService.DELETION_RESPONSE);
        assertThat(events(AuditActions.DATA_REQUEST_TRANSITIONED, deletion.requestId()))
                .isEqualTo(2);
        List<String> payloads = jdbc.sql("""
                        select payload::text from audit_outbox
                        where payload->>'action' = 'ACCOUNT_ANONYMIZED' and payload->>'targetRef' = :target
                        """)
                .param("target", "user:" + deletion.userId())
                .query(String.class)
                .list();
        assertThat(payloads).hasSize(1);
        JsonNode event = json.readTree(payloads.getFirst());
        assertThat(event.get("actorType").asText()).isEqualTo("ADMIN");
        assertThat(event.get("actor").asText())
                .isEqualTo(deletion.admin().userId().toString());
        assertThat(event.get("tenantId").asText()).isEqualTo(deletion.tenantId().toString());
        JsonNode details = event.get("details");
        assertThat(details.size()).isEqualTo(3);
        assertThat(details.get("botsStopped").asText()).isEqualTo("1");
        assertThat(details.get("sessionsRevoked").asText()).isEqualTo(String.valueOf(result.sessionsRevoked()));
        assertThat(details.get("feedbackDeleted").asText()).isEqualTo("1");
        assertThat(payloads.getFirst()).doesNotContain(deletion.wallet());
    }

    @Test
    void aPersonWithoutBotsCommentsOrOtherSessionsIsAnonymizedWithZeroCounts() throws Exception {
        Deletion deletion = deletionInProgress();

        AnonymizationResult result = anonymize(deletion);

        assertThat(result.botsStopped()).isZero();
        assertThat(result.feedbackDeleted()).isZero();
        assertThat(result.sessionsRevoked()).isEqualTo(1);
        assertThat(accountStatus(deletion.userId())).isEqualTo("ANONYMIZED");
    }

    @Test
    void anotherPersonKeepsItsAccountWalletSessionsAndBots() throws Exception {
        Deletion deletion = deletionInProgress();
        WalletGuest other = newWalletGuest();
        UUID otherBot = seedBot(other.principal(), "ACTIVE");

        anonymize(deletion);

        assertThat(accountStatus(other.principal().userId())).isEqualTo("ACTIVE");
        assertThat(walletRow(other.principal().userId()).get("address"))
                .isEqualTo(other.wallet().address());
        assertThat(activeSessions(other.principal().userId())).isEqualTo(1);
        assertThat(botState(otherBot)).isEqualTo("ACTIVE");
        assertThat(challengesOf(other.wallet().address())).isPositive();
    }

    @Test
    void anEmailDeletionWithAConfirmedIdentityIsAnonymizedThroughTheVerification() throws Exception {
        WalletGuest guest = newWalletGuest();
        com.memetrade.identity.api.Principal admin = newAdministrator();
        String bearer = sessionOf(admin).bearer();
        String id = registered(bearer, "DELETION", guest.principal().userId());
        String message =
                body(calls.startVerification(bearer, id), 200).get("message").asText();
        body(calls.confirm(bearer, id, guest.wallet().sign(message)), 200);

        AnonymizationResult result = anonymize(admin, id, UUID.randomUUID());

        assertThat(result.alreadyAnonymized()).isFalse();
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(accountStatus(guest.principal().userId())).isEqualTo("ANONYMIZED");
    }
}
