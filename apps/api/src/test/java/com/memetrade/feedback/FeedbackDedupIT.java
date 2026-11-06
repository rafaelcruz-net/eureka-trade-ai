package com.memetrade.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.Concurrently;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 7 of U8, written BEFORE the feedback code from the rules (BR6.2, BR6.3, BR6.4, AC6.1.2, NFR4.2, team.md
 * "deduplicação por chave de idempotência"), through {@code POST /api/v1/feedback} with real guest sessions: the same key
 * and body give ONE row, another body is 409, and 400 and 429 are never stored for their key. Tenant and person come
 * from the token; a tenant in the body is refused; an administrator token is refused.
 */
class FeedbackDedupIT extends AbstractU8IT {

    @Test
    void theSameKeyAndBodyGiveOneRowAndTheSameAnswer() throws Exception {
        Guest guest = newGuest();
        UUID key = UUID.randomUUID();

        MockHttpServletResponse first = postFeedback(guest.bearer(), "S3", "Lista lenta hoje.", key);
        MockHttpServletResponse second = postFeedback(guest.bearer(), "S3", "Lista lenta hoje.", key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(body(second).get("feedbackId")).isEqualTo(body(first).get("feedbackId"));
        assertThat(rows(guest)).isEqualTo(1);
    }

    @Test
    void theSameKeyAtTheSameTimeStillGivesOneRow() throws Exception {
        Guest guest = newGuest();
        UUID key = UUID.randomUUID();
        Callable<MockHttpServletResponse> send = () -> postFeedback(guest.bearer(), "S5", "Ao mesmo tempo.", key);

        List<Concurrently.Outcome<MockHttpServletResponse>> answers = Concurrently.run(send, send, send, send, send);

        assertThat(answers).noneMatch(Concurrently.Outcome::failed);
        assertThat(answers.stream().map(answer -> answer.value().getStatus()))
                .allMatch(status -> status == 201 || status == 409);
        assertThat(answers.stream().filter(answer -> answer.value().getStatus() == 201))
                .isNotEmpty();
        assertThat(rows(guest)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherBodyIsAConflict() throws Exception {
        Guest guest = newGuest();
        UUID key = UUID.randomUUID();
        postFeedback(guest.bearer(), "S3", "Primeiro texto.", key);

        MockHttpServletResponse conflict = postFeedback(guest.bearer(), "S3", "Outro texto.", key);

        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(body(conflict).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(rows(guest)).isEqualTo(1);
    }

    /** BR6.3: a 400 is not stored; the corrected body with the same key is processed. */
    @Test
    void aRefusedBodyIsNotStoredForItsKey() throws Exception {
        Guest guest = newGuest();
        UUID key = UUID.randomUUID();

        MockHttpServletResponse refused = postFeedback(guest.bearer(), "S3", "   ", key);
        MockHttpServletResponse accepted = postFeedback(guest.bearer(), "S3", "Agora com texto.", key);

        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(body(refused).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(accepted.getStatus()).isEqualTo(201);
        assertThat(rows(guest)).isEqualTo(1);
    }

    /** BR6.3: a 429 carries Retry-After and is not stored; after the window the same key and body are processed. */
    @Test
    void aRateLimitedSubmissionIsProcessedAgainAfterTheWindow() throws Exception {
        Guest guest = newGuest();
        seedComments(guest, 10);
        UUID key = UUID.randomUUID();

        MockHttpServletResponse limited = postFeedback(guest.bearer(), "S2", "Mais um comentário.", key);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(body(limited).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(limited.getHeader("Retry-After")).matches("\\d+");

        // The seeds were 55 minutes old: 10 minutes later they left the window; the 15-minute token is still valid.
        clock.advance(Duration.ofMinutes(10));
        MockHttpServletResponse accepted = postFeedback(guest.bearer(), "S2", "Mais um comentário.", key);

        assertThat(accepted.getStatus()).isEqualTo(201);
        assertThat(rows(guest)).isEqualTo(11);
    }

    /** BR6.2, AC6.1.2: the tenant and the person come from the token; a tenant in the body is refused (closed body). */
    @Test
    void theTenantComesFromTheTokenNeverFromTheBody() throws Exception {
        Guest guest = newGuest();
        Guest other = newGuest();
        String withTenant = "{\"screen\":\"S1\",\"text\":\"Tenant no corpo.\",\"tenantId\":\""
                + other.principal().tenantId() + "\"}";

        MockHttpServletResponse refused = postFeedbackBody(guest.bearer(), withTenant, UUID.randomUUID());
        MockHttpServletResponse accepted =
                postFeedback(guest.bearer(), "S1", "Sem tenant no corpo.", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(accepted.getStatus()).isEqualTo(201);
        UUID stored = UUID.fromString(body(accepted).get("feedbackId").asText());
        assertThat(jdbc.sql("select tenant_id from feedback_entry where feedback_id = :id")
                        .param("id", stored)
                        .query(UUID.class)
                        .single())
                .isEqualTo(guest.principal().tenantId());
        assertThat(jdbc.sql("select user_id from feedback_entry where feedback_id = :id")
                        .param("id", stored)
                        .query(UUID.class)
                        .single())
                .isEqualTo(guest.principal().userId());
        assertThat(rows(other)).isZero();
    }

    /** BR6.2 with BR1.5: only a guest sends feedback; an administrator token is 403 FORBIDDEN_ROLE. */
    @Test
    void anAdministratorCannotSendFeedback() throws Exception {
        MockHttpServletResponse refused =
                postFeedback(sessionOf(newAdministrator()).bearer(), "S1", "Sou administrador.", UUID.randomUUID());

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("FORBIDDEN_ROLE");
    }

    /** The answer of a 201 is the identifier and the instant, never the text. */
    @Test
    void theAnswerCarriesOnlyTheIdentifierAndTheInstant() throws Exception {
        Guest guest = newGuest();

        JsonNode answer = body(postFeedback(guest.bearer(), "S8", "Resposta curta.", UUID.randomUUID()));

        assertThat(answer.has("feedbackId")).isTrue();
        assertThat(answer.get("createdAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(answer.toString()).doesNotContain("Resposta curta.");
    }

    private long rows(Guest guest) {
        return jdbc.sql("select count(*) from feedback_entry where user_id = :user")
                .param("user", guest.principal().userId())
                .query(Long.class)
                .single();
    }

    /** Ten comments of the guest within the last hour of the fixed clock, written like the service writes them. */
    private void seedComments(Guest guest, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                            values (:id, :tenant, :user, 'S2', :text, :at)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenant", guest.principal().tenantId())
                    .param("user", guest.principal().userId())
                    .param("text", "semente " + i)
                    .param("at", Timestamp.from(clock.instant().minus(Duration.ofMinutes(55))))
                    .update();
        }
    }
}
