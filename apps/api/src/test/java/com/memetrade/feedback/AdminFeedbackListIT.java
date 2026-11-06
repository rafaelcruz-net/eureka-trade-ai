package com.memetrade.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminSessions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 8 of U8 (BR6.5, BR6.6, AC6.1.4, NFR5.1): the administrator reads the comments newest first, by cursor, with the
 * tenant name, the screen, the instant and the text exactly as sent; never the person nor the wallet. A guest token is
 * refused.
 */
class AdminFeedbackListIT extends AbstractU8IT {

    @Test
    void theListIsNewestFirstWithTheTenantNameAndNeverThePerson() throws Exception {
        Guest guest = newGuest();
        String marker = "marcador-" + UUID.randomUUID();
        postFeedback(guest.bearer(), "S3", "Primeiro " + marker, UUID.randomUUID());
        clock.advance(Duration.ofSeconds(5));
        postFeedback(guest.bearer(), "S6", "<b>Segundo</b> " + marker, UUID.randomUUID());

        // The database is shared by the whole run and other classes leave comments at any instant, so the test walks
        // the whole list and checks only its own two comments, in the order the list returned them.
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());
        List<JsonNode> own = new ArrayList<>();
        String query = "?limit=100";
        while (true) {
            JsonNode page = body(list(admin, query));
            assertThat(page.toString())
                    .doesNotContain(guest.principal().userId().toString())
                    .doesNotContain("userId")
                    .doesNotContain("wallet");
            page.get("items").forEach(item -> {
                if (item.get("text").asText().endsWith(marker)) {
                    own.add(item);
                }
            });
            if (!page.hasNonNull("nextCursor")) {
                break;
            }
            query = "?limit=100&cursor=" + page.get("nextCursor").asText();
        }

        assertThat(own).hasSize(2);
        JsonNode newest = own.get(0);
        assertThat(newest.get("text").asText()).isEqualTo("<b>Segundo</b> " + marker);
        assertThat(newest.get("screen").asText()).isEqualTo("S6");
        assertThat(newest.get("tenantId").asText())
                .isEqualTo(guest.principal().tenantId().toString());
        assertThat(newest.get("tenantName").asText()).isEqualTo("Test tenant");
        assertThat(own.get(1).get("text").asText()).isEqualTo("Primeiro " + marker);
    }

    @Test
    void theCursorWalksEveryCommentOnceWithoutRepeating() throws Exception {
        Guest guest = newGuest();
        for (int i = 0; i < 7; i++) {
            postFeedback(guest.bearer(), "S2", "Página " + i, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));
        }
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());
        List<String> seen = new ArrayList<>();
        String query = "?limit=2";
        for (int page = 0; page < 3; page++) {
            JsonNode body = body(list(admin, query));
            body.get("items").forEach(item -> seen.add(item.get("feedbackId").asText()));
            if (!body.hasNonNull("nextCursor")) {
                break;
            }
            query = "?limit=2&cursor=" + body.get("nextCursor").asText();
        }

        assertThat(seen).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void aLimitOutsideOneToOneHundredOrABadCursorIsRefused() throws Exception {
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());

        assertThat(list(admin, "?limit=0").getStatus()).isEqualTo(400);
        assertThat(list(admin, "?limit=101").getStatus()).isEqualTo(400);
        assertThat(list(admin, "?limit=abc").getStatus()).isEqualTo(400);
        assertThat(list(admin, "?cursor=nao-e-um-cursor").getStatus()).isEqualTo(400);
        assertThat(list(admin, "?limit=100").getStatus()).isEqualTo(200);
    }

    /** BR6.5: there is no guest route that reads feedback; the administrative one refuses a guest token. */
    @Test
    void aGuestCannotReadTheFeedback() throws Exception {
        Guest guest = newGuest();

        MockHttpServletResponse refused = mvc.perform(
                        get("/api/v1/admin/feedback").header("Authorization", guest.bearer()))
                .andReturn()
                .getResponse();

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("FORBIDDEN_ROLE");
    }

    private MockHttpServletResponse list(AdminSessions.AdminSession admin, String query) throws Exception {
        return mvc.perform(get("/api/v1/admin/feedback" + query).header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse();
    }
}
