package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.identity.api.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 7 of u5-anonymization (P2 {@code adminAnonymizeAccount}; US6.1; BR5.1, BR5.5; NFR2.1), after the route: the role,
 * every refusal as RFC 7807 with its stable code and nothing changed, the success with only identifiers and counts, the
 * repetition with the same key and with another key, and the anonymized flag of the list and of the detail.
 */
class AdminAnonymizationIT extends AbstractAnonymizationIT {

    @Test
    void aGuestTokenIsForbidden() throws Exception {
        Deletion deletion = deletionInProgress();

        MockHttpServletResponse refused =
                calls.anonymize(deletion.guest().bearer(), deletion.requestId(), "ANONIMIZAR", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(calls.body(refused).get("code").asText()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(accountStatus(deletion.userId())).isEqualTo("ACTIVE");
    }

    @Test
    void theAdministratorAnonymizesAndTheAnswerHasOnlyIdentifiersAndCounts() throws Exception {
        Deletion deletion = deletionInProgress();
        seedBot(deletion.guest().principal(), "ACTIVE");
        seedFeedback(deletion.guest().principal(), 2);
        String admin = sessionOf(deletion.admin()).bearer();

        JsonNode result = body(calls.anonymize(admin, deletion.requestId(), "ANONIMIZAR", UUID.randomUUID()), 200);

        assertThat(result.get("requestId").asText()).isEqualTo(deletion.requestId());
        assertThat(result.get("userId").asText()).isEqualTo(deletion.userId().toString());
        assertThat(result.get("botsStopped").asInt()).isEqualTo(1);
        assertThat(result.get("feedbackDeleted").asInt()).isEqualTo(2);
        assertThat(result.get("sessionsRevoked").asInt()).isEqualTo(1);
        assertThat(result.get("alreadyAnonymized").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain(deletion.wallet());
        JsonNode detail = body(calls.adminGet(admin, deletion.requestId()), 200);
        assertThat(detail.get("status").asText()).isEqualTo("DONE");
        assertThat(detail.get("accountAnonymized").asBoolean()).isTrue();
    }

    @Test
    void aWrongConfirmationIs422ConfirmationRequiredAndNothingChanges() throws Exception {
        Deletion deletion = deletionInProgress();
        String admin = sessionOf(deletion.admin()).bearer();

        MockHttpServletResponse refused = calls.anonymize(admin, deletion.requestId(), "anonimizar", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refused.getContentType()).startsWith("application/problem+json");
        JsonNode problem = calls.body(refused);
        assertThat(problem.get("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(problem.has("correlationId")).isTrue();
        assertThat(accountStatus(deletion.userId())).isEqualTo("ACTIVE");
        assertThat(status(deletion.requestId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aRequestThatIsNotADeletionInProgressIsNotEligible() throws Exception {
        WalletGuest guest = newWalletGuest();
        Principal adminPrincipal = newAdministrator();
        String admin = sessionOf(adminPrincipal).bearer();
        String received = created(guest.bearer(), "DELETION");
        String access = created(guest.bearer(), "ACCESS");
        body(calls.transition(admin, access, "IN_PROGRESS", null, null, false), 200);

        assertCode(calls.anonymize(admin, received, "ANONIMIZAR", UUID.randomUUID()), 409, "REQUEST_NOT_ELIGIBLE");
        assertCode(calls.anonymize(admin, access, "ANONIMIZAR", UUID.randomUUID()), 409, "REQUEST_NOT_ELIGIBLE");
        assertThat(accountStatus(guest.principal().userId())).isEqualTo("ACTIVE");
    }

    @Test
    void anEmailDeletionWithoutAnAccountOrWithoutTheConfirmedIdentityIsRefused() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = sessionOf(newAdministrator()).bearer();
        String withoutAccount = registered(admin, "DELETION", null);
        body(calls.transition(admin, withoutAccount, "IN_PROGRESS", null, null, false), 200);
        String unverified = registered(admin, "DELETION", guest.principal().userId());
        jdbc.sql("update data_subject_request set status = 'IN_PROGRESS' where request_id = :id")
                .param("id", UUID.fromString(unverified))
                .update();

        assertCode(calls.anonymize(admin, withoutAccount, "ANONIMIZAR", UUID.randomUUID()), 409, "NO_LINKED_ACCOUNT");
        assertCode(calls.anonymize(admin, unverified, "ANONIMIZAR", UUID.randomUUID()), 409, "IDENTITY_NOT_VERIFIED");
        assertThat(accountStatus(guest.principal().userId())).isEqualTo("ACTIVE");
    }

    @Test
    void anAdministratorAccountIsNeverAnonymized() throws Exception {
        Principal otherAdmin = newAdministrator();
        String admin = sessionOf(newAdministrator()).bearer();
        String id = registered(admin, "DELETION", otherAdmin.userId());
        jdbc.sql(
                        "update data_subject_request set status = 'IN_PROGRESS', identity_verified = true where request_id = :id")
                .param("id", UUID.fromString(id))
                .update();

        assertCode(calls.anonymize(admin, id, "ANONIMIZAR", UUID.randomUUID()), 409, "ADMIN_ACCOUNT_NOT_ANONYMIZABLE");
        assertThat(accountStatus(otherAdmin.userId())).isEqualTo("ACTIVE");
    }

    @Test
    void anUnknownRequestIsNotFound() throws Exception {
        String admin = sessionOf(newAdministrator()).bearer();

        assertCode(
                calls.anonymize(admin, UUID.randomUUID().toString(), "ANONIMIZAR", UUID.randomUUID()),
                404,
                "NOT_FOUND");
    }

    @Test
    void theSameKeyReplaysAndAnotherKeyAnswersTheRepetition() throws Exception {
        Deletion deletion = deletionInProgress();
        String admin = sessionOf(deletion.admin()).bearer();
        UUID key = UUID.randomUUID();

        JsonNode first = body(calls.anonymize(admin, deletion.requestId(), "ANONIMIZAR", key), 200);
        JsonNode replay = body(calls.anonymize(admin, deletion.requestId(), "ANONIMIZAR", key), 200);
        JsonNode again = body(calls.anonymize(admin, deletion.requestId(), "ANONIMIZAR", UUID.randomUUID()), 200);

        assertThat(replay).isEqualTo(first);
        assertThat(again.get("alreadyAnonymized").asBoolean()).isTrue();
        assertThat(again.get("anonymizedAt").asText())
                .isEqualTo(first.get("anonymizedAt").asText());
        assertThat(anonymizedEvents(deletion.userId())).isEqualTo(1);
    }

    @Test
    void aMissingKeyIsRefusedBeforeAnything() throws Exception {
        Deletion deletion = deletionInProgress();
        String admin = sessionOf(deletion.admin()).bearer();

        MockHttpServletResponse refused = calls.anonymize(admin, deletion.requestId(), "ANONIMIZAR", null);

        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(accountStatus(deletion.userId())).isEqualTo("ACTIVE");
    }

    @Test
    void theListShowsTheAnonymizedAccountWithoutAnyWallet() throws Exception {
        Deletion deletion = deletionInProgress();
        String admin = sessionOf(deletion.admin()).bearer();
        body(calls.anonymize(admin, deletion.requestId(), "ANONIMIZAR", UUID.randomUUID()), 200);

        JsonNode item = null;
        String query = "status=DONE&limit=100";
        for (int pages = 0; item == null && query != null && pages < 200; pages++) {
            JsonNode page = body(calls.adminList(admin, query), 200);
            assertThat(page.toString()).doesNotContain(deletion.wallet());
            for (JsonNode candidate : page.get("items")) {
                if (candidate.get("requestId").asText().equals(deletion.requestId())) {
                    item = candidate;
                }
            }
            query = page.hasNonNull("nextCursor")
                    ? "status=DONE&limit=100&cursor="
                            + java.net.URLEncoder.encode(
                                    page.get("nextCursor").asText(), java.nio.charset.StandardCharsets.UTF_8)
                    : null;
        }
        assertThat(item).as("the anonymized request is in the list of DONE").isNotNull();
        assertThat(item.get("accountAnonymized").asBoolean()).isTrue();
    }

    private void assertCode(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(calls.body(response).get("code").asText()).isEqualTo(code);
    }
}
