package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.AbstractRealOpsIT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 12 of the U5, written after the code (R4, BR1.2 to BR7.2, U5-NFR6.1 to U5-NFR6.3): the routes of the
 * administration of the REAL. Only ADMIN; every change needs an Idempotency-Key (the same key and body give the same
 * answer, another body 409, no key 400) and a reason; refusals are RFC 7807 with their stable code; the view never
 * shows a key nor the full main wallet.
 */
@Tag("u5-real-operations")
class RealAdminRoutesIT extends AbstractRealOpsIT {

    private static final long SOL = 1_000_000_000L;

    @Autowired
    private AdminSessions sessions;

    /** One administrator per test: the idempotency record is scoped by the administrator. */
    private String bearer;

    private MockHttpServletResponse admin(MockHttpServletRequestBuilder request, String key) throws Exception {
        if (bearer == null) {
            bearer = sessions.openAdminSession().bearer();
        }
        MockHttpServletRequestBuilder built =
                request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            built = built.header("Idempotency-Key", key);
        }
        return mvc.perform(built).andReturn().getResponse();
    }

    private MockHttpServletResponse admin(MockHttpServletRequestBuilder request) throws Exception {
        return admin(request, UUID.randomUUID().toString());
    }

    private static String reason(String text) {
        return "{\"reason\":\"" + text + "\"}";
    }

    @Test
    void aGuestTokenIsRefusedOnTheRealAdministration() throws Exception {
        OpsPerson guest = activePerson();

        MockHttpServletResponse users = call(guest.person(), get("/api/v1/admin/real/users"));
        MockHttpServletResponse killSwitch =
                call(guest.person(), put("/api/v1/admin/real/kill-switch").content(reason("x")));

        assertThat(users.getStatus()).isEqualTo(403);
        assertThat(body(users).get("code").asText()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(killSwitch.getStatus()).isEqualTo(403);
    }

    @Test
    void theReleaseRouteCreatesTheQuarantineAndARepetitionGivesTheSameAnswer() throws Exception {
        OpsPerson person = activePerson();
        String key = UUID.randomUUID().toString();
        String path = "/api/v1/admin/real/users/" + person.userId() + "/release";
        String content = "{\"network\":\"LOCALNET\",\"reason\":\"convidado aprovado\"}";

        MockHttpServletResponse first = admin(post(path).content(content), key);
        MockHttpServletResponse again = admin(post(path).content(content), key);
        MockHttpServletResponse other = admin(post(path).content("{\"network\":\"DEVNET\",\"reason\":\"outra\"}"), key);

        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        assertThat(body(first).get("state").asText()).isEqualTo("QUARANTINE");
        assertThat(again.getStatus()).isEqualTo(201);
        assertThat(body(again).get("releaseId")).isEqualTo(body(first).get("releaseId"));
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(body(other).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void aSecondReleaseIsAProblemWithItsStableCodeAndNotStored() throws Exception {
        OpsPerson person = activePerson();
        String path = "/api/v1/admin/real/users/" + person.userId() + "/release";
        admin(post(path).content("{\"network\":\"LOCALNET\",\"reason\":\"um\"}"));

        MockHttpServletResponse refused = admin(post(path).content("{\"network\":\"LOCALNET\",\"reason\":\"dois\"}"));

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(body(refused).get("code").asText()).isEqualTo("RELEASE_EXISTS");
        assertThat(body(refused).has("correlationId")).isTrue();
    }

    @Test
    void everyChangeNeedsAnIdempotencyKeyAndAReason() throws Exception {
        OpsPerson person = activePerson();
        String path = "/api/v1/admin/real/users/" + person.userId() + "/release";

        MockHttpServletResponse withoutKey =
                admin(post(path).content("{\"network\":\"LOCALNET\",\"reason\":\"x\"}"), null);
        MockHttpServletResponse withoutReason =
                admin(post(path).content("{\"network\":\"LOCALNET\",\"reason\":\" \"}"));

        assertThat(withoutKey.getStatus()).isEqualTo(400);
        assertThat(withoutReason.getStatus()).isEqualTo(422);
        assertThat(body(withoutReason).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void theRevocationRouteTakesTheLockOfThePersonAndCancelsTheUnsentOrders() throws Exception {
        OpsPerson person = activePerson();
        releaseOf(person.userId(), "LOCALNET", "ACTIVE", Duration.ofHours(30));
        UUID quoted = order(person, "QUOTED");

        MockHttpServletResponse revoked = admin(delete("/api/v1/admin/real/users/" + person.userId() + "/release")
                .content(reason("fraude")));

        assertThat(revoked.getStatus()).isEqualTo(200);
        assertThat(body(revoked).get("revoked").asBoolean()).isTrue();
        assertThat(body(revoked).get("cancelledOrders").asInt()).isEqualTo(1);
        assertThat(orderState(quoted)).isEqualTo("CANCELLED:REAL_DISABLED");
    }

    @Test
    void theCapRouteOnlyLowers() throws Exception {
        OpsPerson person = activePerson();
        String path = "/api/v1/admin/real/users/" + person.userId() + "/cap";

        MockHttpServletResponse lowered =
                admin(put(path).content("{\"capLamports\":\"3000000000\",\"reason\":\"risco\"}"));
        MockHttpServletResponse raised =
                admin(put(path).content("{\"capLamports\":\"5000000000\",\"reason\":\"risco\"}"));
        MockHttpServletResponse malformed = admin(put(path).content("{\"capLamports\":\"3.5\",\"reason\":\"risco\"}"));

        assertThat(lowered.getStatus()).isEqualTo(200);
        assertThat(body(lowered).get("capLamports").asText()).isEqualTo("3000000000");
        assertThat(raised.getStatus()).isEqualTo(422);
        assertThat(body(raised).get("code").asText()).isEqualTo("CAP_ONLY_LOWERED");
        assertThat(malformed.getStatus()).isEqualTo(422);
    }

    @Test
    void theBlockListIsBlockedListedAndUnblocked() throws Exception {
        String mint = SyntheticIds.mint();

        MockHttpServletResponse blocked = admin(
                post("/api/v1/admin/real/token-blocks").content("{\"mint\":\"" + mint + "\",\"reason\":\"rug pull\"}"));
        MockHttpServletResponse listed = admin(get("/api/v1/admin/real/token-blocks"));
        assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
        JsonNode list = body(listed);
        MockHttpServletResponse unblocked = admin(delete("/api/v1/admin/real/token-blocks/" + mint));
        MockHttpServletResponse invalid =
                admin(post("/api/v1/admin/real/token-blocks").content("{\"mint\":\"not-a-mint\",\"reason\":\"x\"}"));

        assertThat(blocked.getStatus()).isEqualTo(201);
        assertThat(list.get("items").findValuesAsText("mint")).contains(mint);
        assertThat(unblocked.getStatus()).isEqualTo(200);
        assertThat(body(unblocked).get("unblocked").asBoolean()).isTrue();
        assertThat(invalid.getStatus()).isEqualTo(422);
    }

    @Test
    void theKillSwitchIsSwitchedOnWithItsCancellationsAndOffAgain() throws Exception {
        UUID quoted = order(activePerson(), "QUOTED");

        MockHttpServletResponse on = admin(put("/api/v1/admin/real/kill-switch").content(reason("incidente")));
        MockHttpServletResponse current = admin(get("/api/v1/admin/real/kill-switch"));
        MockHttpServletResponse off =
                admin(delete("/api/v1/admin/real/kill-switch").content(reason("normalizado")));

        assertThat(on.getStatus()).isEqualTo(200);
        assertThat(body(on).get("active").asBoolean()).isTrue();
        assertThat(body(on).get("cancelledOrders").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(orderState(quoted)).isEqualTo("CANCELLED:KILL_SWITCH");
        assertThat(body(current).get("active").asBoolean()).isTrue();
        assertThat(body(off).get("active").asBoolean()).isFalse();
        assertThat(body(off).get("changed").asBoolean()).isTrue();
    }

    @Test
    void theDivergencesAreListedAndAWrongCompensationIsAProblem() throws Exception {
        OpsPerson person = activePerson();
        UUID run = UUID.randomUUID();
        UUID divergence = UUID.randomUUID();
        jdbc.sql("insert into real_reconciliation_run (run_id, tenant_id, bot_wallet_id, trigger, slot, chain_lamports,"
                        + " ledger_lamports, result, started_at, finished_at) values (:r, :t, :w, 'SCHEDULE', 1, 1001000,"
                        + " 1000000, 'DIVERGENT', now(), now())")
                .param("r", run)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .update();
        jdbc.sql("insert into real_divergence (divergence_id, run_id, last_run_id, tenant_id, bot_wallet_id,"
                        + " lamports_diff, opened_at, updated_at) values (:d, :r, :r, :t, :w, 1000, now(), now())")
                .param("d", divergence)
                .param("r", run)
                .param("t", person.tenantId())
                .param("w", person.botWalletId())
                .update();
        String path = "/api/v1/admin/real/divergences/" + divergence + "/compensation";

        JsonNode list = body(admin(get("/api/v1/admin/real/divergences?limit=50")));
        MockHttpServletResponse wrong =
                admin(post(path).content("{\"items\":[{\"asset\":\"SOL\",\"amount\":\"999\"}],\"reason\":\"erro\"}"));
        MockHttpServletResponse right = admin(
                post(path).content("{\"items\":[{\"asset\":\"SOL\",\"amount\":\"1000\"}],\"reason\":\"deposito\"}"));

        assertThat(list.get("items").findValuesAsText("divergenceId")).contains(divergence.toString());
        assertThat(wrong.getStatus()).isEqualTo(422);
        assertThat(body(wrong).get("code").asText()).isEqualTo("COMPENSATION_MISMATCH");
        assertThat(right.getStatus()).isEqualTo(201);
        assertThat(body(right).get("state").asText()).isEqualTo("COMPENSATED");
        assertThat(body(right).get("lamportsDiff").asText()).isEqualTo("1000");
    }

    /** S21, U5-NFR6.3: the main wallet abbreviated, the bot wallet in full, no key at all. */
    @Test
    void theViewShowsTheMainWalletAbbreviatedAndNoKey() throws Exception {
        OpsPerson person = activePerson();
        deposit(person, 2 * SOL);
        releaseOf(person.userId(), "LOCALNET", "ACTIVE", Duration.ofHours(30));

        MockHttpServletResponse detail = admin(get("/api/v1/admin/real/users/" + person.userId()));
        String text = detail.getContentAsString();
        JsonNode view = body(detail);

        assertThat(detail.getStatus()).isEqualTo(200);
        assertThat(view.get("mainWalletShort").asText())
                .isEqualTo(person.mainAddress().substring(0, 4) + "…"
                        + person.mainAddress().substring(person.mainAddress().length() - 4));
        assertThat(text).doesNotContain(person.mainAddress());
        assertThat(view.get("botWalletAddress").asText()).isEqualTo(person.botAddress());
        assertThat(text.toLowerCase(java.util.Locale.ROOT)).doesNotContain("key");
        assertThat(view.get("balance").get("totalLamports").asText()).isEqualTo(Long.toString(2 * SOL));
        assertThat(view.get("release").get("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void theListOfPeopleIsPagedByCursor() throws Exception {
        activePerson();
        activePerson();

        JsonNode first = body(admin(get("/api/v1/admin/real/users?limit=1")));
        JsonNode second = body(admin(get("/api/v1/admin/real/users?limit=1&cursor="
                + first.get("nextCursor").asText())));

        assertThat(first.get("items")).hasSize(1);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("userId"))
                .isNotEqualTo(first.get("items").get(0).get("userId"));
    }

    @Test
    void anUnknownPersonIsNotFoundAndARestoreWithoutMarkerToo() throws Exception {
        MockHttpServletResponse unknown = admin(get("/api/v1/admin/real/users/" + UUID.randomUUID()));
        MockHttpServletResponse restore =
                admin(post("/api/v1/admin/real/restore/confirmation").content(reason("conferido")));

        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(restore.getStatus()).isEqualTo(404);
    }
}
