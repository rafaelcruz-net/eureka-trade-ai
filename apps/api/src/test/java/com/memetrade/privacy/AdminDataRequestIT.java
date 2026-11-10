package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.LedgerRows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 6 of u4-data-requests (P2; BR4.5 to BR4.10, BR4.16, BR4.17; US5.3 to US5.6, US5.9): the administrator on the
 * real api and database - role, list with filter and cursor, transitions, the Access summary through J1 to J4 with known
 * counts, the e-mail registration and the record of the reply.
 */
class AdminDataRequestIT extends AbstractDataRequestIT {

    @Test
    void aGuestTokenIsRefusedOnTheAdministrativeRoutes() throws Exception {
        WalletGuest guest = newWalletGuest();

        MockHttpServletResponse list = calls.adminList(guest.bearer(), null);

        assertThat(list.getStatus()).isEqualTo(403);
        assertThat(calls.body(list).get("code").asText()).isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void theAdministratorWalksTheRequestToDoneAndThePersonSeesTheResponse() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String id = created(guest.bearer(), "CORRECTION");

        JsonNode detail = body(calls.adminGet(admin, id), 200);
        JsonNode started = body(calls.transition(admin, id, "IN_PROGRESS", null, null, false), 200);
        JsonNode done = body(calls.transition(admin, id, "DONE", "Seu nome foi corrigido.", null, false), 200);
        JsonNode seen = body(calls.get(guest.bearer(), id), 200);

        assertThat(detail.get("details").asText()).startsWith("Texto sintético de teste");
        assertThat(detail.get("origin").asText()).isEqualTo("ACCOUNT");
        assertThat(detail.get("contact").isNull()).isTrue();
        assertThat(started.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(seen.get("response").asText()).isEqualTo("Seu nome foi corrigido.");
        assertThat(events(AuditActions.DATA_REQUEST_TRANSITIONED, id)).isEqualTo(2);
        MockHttpServletResponse again = calls.transition(admin, id, "IN_PROGRESS", null, null, false);
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(calls.body(again).get("code").asText()).isEqualTo("DATA_REQUEST_CLOSED");
    }

    @Test
    void aRefusalWithoutAReasonIs422AndTheAdministratorEventHasNoText() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String id = created(guest.bearer(), "OTHER");
        body(calls.transition(admin, id, "IN_PROGRESS", null, null, false), 200);

        MockHttpServletResponse withoutReason = calls.transition(admin, id, "REFUSED", null, null, false);
        JsonNode refused = body(calls.transition(admin, id, "REFUSED", null, "Motivo sintético.", false), 200);

        assertThat(withoutReason.getStatus()).isEqualTo(422);
        assertThat(refused.get("refusalReason").asText()).isEqualTo("Motivo sintético.");
        List<String> payloads =
                jdbc.sql("""
                        select payload::text from audit_outbox
                        where payload->>'action' = 'DATA_REQUEST_TRANSITIONED' and payload->'details'->>'requestId' = :id
                        """).param("id", id).query(String.class).list();
        assertThat(payloads).hasSize(2).allSatisfy(payload -> {
            assertThat(payload).doesNotContain("Motivo sintético");
            assertThat(json.readTree(payload).get("actorType").asText()).isEqualTo("ADMIN");
        });
        assertThat(json.readTree(payloads.getLast()).get("tenantId").asText())
                .isEqualTo(guest.principal().tenantId().toString());
    }

    @Test
    void theAccessSummaryCarriesTheKnownCountsAndIsReleasedAsASnapshot() throws Exception {
        WalletGuest guest = newWalletGuest();
        Principal person = guest.principal();
        seedBots(person, 2);
        LedgerRows ledger = new LedgerRows(jdbc);
        UUID botId = ledger.account(person, "OPEN");
        for (int i = 0; i < 3; i++) {
            ledger.order(person, botId, "CLOSE", "FILLED", null, "key-" + UUID.randomUUID());
        }
        seedFeedback(person, 4);
        String admin = adminBearer();
        String id = created(guest.bearer(), "ACCESS");

        JsonNode preview = body(calls.adminGet(admin, id), 200).get("accessSummaryPreview");
        body(calls.transition(admin, id, "IN_PROGRESS", null, null, false), 200);
        JsonNode done = body(calls.transition(admin, id, "DONE", "Segue o resumo.", null, true), 200);
        seedBots(person, 1);
        JsonNode seen = body(calls.get(guest.bearer(), id), 200).get("accessSummary");

        assertThat(preview.get("botCount").asLong()).isEqualTo(2);
        assertThat(preview.get("paperOrderCount").asLong()).isEqualTo(3);
        assertThat(preview.get("feedbackCount").asLong()).isEqualTo(4);
        assertThat(preview.get("requests")).hasSize(1);
        assertThat(preview.get("acceptedVersions").get(0).get("version").asText())
                .isEqualTo("v1");
        assertThat(preview.get("walletMasked").asText())
                .isNotEqualTo(guest.wallet().address());
        assertThat(done.get("accessSummary").get("botCount").asLong()).isEqualTo(2);
        assertThat(seen.get("botCount").asLong())
                .as("the snapshot does not follow later changes")
                .isEqualTo(2);
        assertThat(seen.toString()).doesNotContain(guest.wallet().address());
    }

    @Test
    void theSummaryOfAnotherPersonNeverMixesIn() throws Exception {
        WalletGuest guest = newWalletGuest();
        WalletGuest other = newWalletGuest();
        seedBots(other.principal(), 3);
        seedFeedback(other.principal(), 2);
        String id = created(guest.bearer(), "ACCESS");

        JsonNode preview = body(calls.adminGet(adminBearer(), id), 200).get("accessSummaryPreview");

        assertThat(preview.get("botCount").asLong()).isZero();
        assertThat(preview.get("feedbackCount").asLong()).isZero();
        assertThat(preview.get("paperOrderCount").asLong()).isZero();
    }

    @Test
    void theListFiltersByStatusPagesByDueDateAndFlagsDueSoonAndOverdue() throws Exception {
        WalletGuest first = newWalletGuest();
        WalletGuest second = newWalletGuest();
        String admin = adminBearer();
        String overdue = created(first.bearer(), "ACCESS");
        clock.advance(Duration.ofDays(5));
        // The access token lives 15 minutes of the fixed clock: sign in again after moving it.
        String soon = created(relogin(second).bearer(), "OTHER");
        clock.advance(Duration.ofDays(13));
        String progressing = created(relogin(first).bearer(), "OTHER");
        body(calls.transition(admin, progressing, "IN_PROGRESS", null, null, false), 200);

        List<JsonNode> received = pages(admin, "status=RECEIVED&limit=5");

        assertThat(received).extracting(item -> item.get("status").asText()).containsOnly("RECEIVED");
        List<String> ids =
                received.stream().map(item -> item.get("requestId").asText()).toList();
        assertThat(ids).contains(overdue, soon).doesNotContain(progressing);
        assertThat(ids.indexOf(overdue)).isLessThan(ids.indexOf(soon));
        JsonNode overdueItem = item(received, overdue);
        JsonNode soonItem = item(received, soon);
        assertThat(overdueItem.get("overdue").asBoolean()).isTrue();
        assertThat(overdueItem.get("dueSoon").asBoolean()).isFalse();
        assertThat(soonItem.get("overdue").asBoolean()).isFalse();
        assertThat(soonItem.get("dueSoon").asBoolean()).isTrue();
        assertThat(soonItem.get("verification").isNull()).isTrue();
        assertThat(calls.adminList(admin, "status=NOPE").getStatus()).isEqualTo(400);
        assertThat(calls.adminList(admin, "limit=0").getStatus()).isEqualTo(400);
        assertThat(calls.adminList(admin, "cursor=bogus").getStatus()).isEqualTo(400);
        JsonNode firstPage = body(calls.adminList(admin, "status=RECEIVED&limit=1"), 200);
        assertThat(firstPage.get("items")).hasSize(1);
        String cursor = firstPage.get("nextCursor").asText();
        assertThat(calls.adminList(admin, "status=DONE&cursor=" + cursor).getStatus())
                .as("a cursor of another filter is refused")
                .isEqualTo(400);
    }

    @Test
    void anEmailRequestIsRegisteredAndARepeatedOpenTypeOfTheAccountIs409() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        created(guest.bearer(), "DELETION");

        MockHttpServletResponse withoutAccount = calls.register(
                admin,
                "OTHER",
                "Pedido sintético.",
                "titular@example.org",
                clock.instant().toString(),
                null);
        MockHttpServletResponse sameType = calls.register(
                admin,
                "DELETION",
                "Pedido sintético.",
                "titular@example.org",
                clock.instant().toString(),
                guest.principal().userId());
        MockHttpServletResponse future = calls.register(
                admin,
                "OTHER",
                "Pedido sintético.",
                "titular@example.org",
                clock.instant().plus(Duration.ofHours(1)).toString(),
                null);
        MockHttpServletResponse unknownAccount = calls.register(
                admin,
                "OTHER",
                "Pedido sintético.",
                "titular@example.org",
                clock.instant().toString(),
                UUID.randomUUID());

        JsonNode registered = body(withoutAccount, 201);
        assertThat(registered.get("origin").asText()).isEqualTo("EMAIL");
        assertThat(registered.get("contact").asText()).isEqualTo("titular@example.org");
        assertThat(registered.get("userId").isNull()).isTrue();
        assertThat(sameType.getStatus()).isEqualTo(409);
        assertThat(calls.body(sameType).get("code").asText()).isEqualTo("DATA_REQUEST_ALREADY_OPEN");
        assertThat(future.getStatus()).isEqualTo(400);
        assertThat(future.getContentAsString()).contains("receivedAt");
        assertThat(unknownAccount.getStatus()).isEqualTo(400);
        assertThat(unknownAccount.getContentAsString()).contains("userId").doesNotContain("titular@example.org");
    }

    @Test
    void anEmailAccessOfAnActiveAccountGoesToVerificationFirst() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String id = registered(admin, "ACCESS", guest.principal().userId());

        MockHttpServletResponse straight = calls.transition(admin, id, "IN_PROGRESS", null, null, false);

        assertThat(straight.getStatus()).isEqualTo(409);
        assertThat(calls.body(straight).get("code").asText()).isEqualTo("INVALID_TRANSITION");
        assertThat(status(id)).isEqualTo("RECEIVED");
    }

    @Test
    void releasingTheSummaryOfAnUnverifiedEmailAccessIs409() throws Exception {
        String admin = adminBearer();
        String id = registered(admin, "ACCESS", null);
        body(calls.transition(admin, id, "IN_PROGRESS", null, null, false), 200);

        MockHttpServletResponse release = calls.transition(admin, id, "DONE", "Resumo.", null, true);

        assertThat(release.getStatus()).isEqualTo(409);
        assertThat(calls.body(release).get("code").asText()).isEqualTo("IDENTITY_NOT_VERIFIED");
    }

    @Test
    void theReplyIsMarkedSentOnlyOnAClosedEmailRequestAndKeepsTheFirstInstant() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String email = registered(admin, "OTHER", null);
        String account = created(guest.bearer(), "OTHER");

        MockHttpServletResponse open = calls.replySent(admin, email);
        body(calls.transition(admin, email, "IN_PROGRESS", null, null, false), 200);
        body(calls.transition(admin, email, "DONE", "Resposta enviada.", null, false), 200);
        Instant sentAt = clock.instant();
        JsonNode marked = body(calls.replySent(admin, email), 200);
        clock.advance(Duration.ofMinutes(10));
        JsonNode again = body(calls.replySent(admin, email), 200);
        body(calls.transition(admin, account, "IN_PROGRESS", null, null, false), 200);
        body(calls.transition(admin, account, "DONE", "Ok.", null, false), 200);

        assertThat(open.getStatus()).isEqualTo(409);
        assertThat(Instant.parse(marked.get("replySentAt").asText())).isEqualTo(sentAt);
        assertThat(Instant.parse(again.get("replySentAt").asText())).isEqualTo(sentAt);
        assertThat(calls.replySent(admin, account).getStatus()).isEqualTo(409);
    }

    @Test
    void anUnknownRequestIs404ForTheAdministrator() throws Exception {
        String admin = adminBearer();

        assertThat(calls.adminGet(admin, UUID.randomUUID().toString()).getStatus())
                .isEqualTo(404);
        assertThat(calls.transition(admin, UUID.randomUUID().toString(), "IN_PROGRESS", null, null, false)
                        .getStatus())
                .isEqualTo(404);
    }

    private List<JsonNode> pages(String admin, String query) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            JsonNode body = body(calls.adminList(admin, query + (cursor == null ? "" : "&cursor=" + cursor)), 200);
            body.get("items").forEach(items::add);
            if (!body.has("nextCursor") || body.get("nextCursor").isNull()) {
                return items;
            }
            cursor = body.get("nextCursor").asText();
        }
        throw new AssertionError("the list never ended");
    }

    private static JsonNode item(List<JsonNode> items, String id) {
        return items.stream()
                .filter(item -> item.get("requestId").asText().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void seedBots(Principal person, int count) {
        jdbc.sql("""
                        insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())
                        on conflict do nothing
                        """).param("t", person.tenantId()).param("u", person.userId()).update();
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            insert into bot (bot_id, tenant_id, owner_user_id, mode, name, template_id,
                                             template_version, state, pause_reasons, draft_step, created_at, updated_at)
                            values (:bot, :t, :u, 'PAPER', 'Bot de teste', 'MOMENTUM_VOLUME', 'v1', 'DRAFT', '', 1,
                                    now(), now())
                            """)
                    .param("bot", UUID.randomUUID())
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .update();
        }
    }

    private void seedFeedback(Principal person, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                            values (:id, :t, :u, 'S1', 'Comentário sintético', :at)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .param("at", java.sql.Timestamp.from(clock.instant()))
                    .update();
        }
    }
}
