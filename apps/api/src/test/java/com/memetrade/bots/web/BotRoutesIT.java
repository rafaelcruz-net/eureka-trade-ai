package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import com.memetrade.testsupport.U5Sql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Passo 13 of U5, WRITTEN BEFORE THE CONTROLLERS (C4; US4.1 to US4.5, US4.8 to US4.10; BR6.1, BR2.9; NFR5.1, NFR5.3,
 * NFR6.2): the routes of the bots through the whole filter chain with real sessions. The own person drives the wizard
 * and the commands; another person of the same tenant and a person of another tenant get 404 and never the data; a
 * mode other than PAPER is 403 and audited; invalid input is 400 or 422 per field; a refused transition is 409.
 */
class BotRoutesIT extends AbstractU5IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private record Guest(String bearer, Principal principal) {}

    @Test
    void theTemplatesCarryTheirParametersWithDefaultRangeAndStep() throws Exception {
        Guest guest = signIn();

        JsonNode templates = body(call(guest, get("/api/v1/strategy-templates")));

        assertThat(templates).hasSize(2);
        JsonNode momentum = templates.get(0);
        assertThat(momentum.get("templateId").asText()).isEqualTo("MOMENTUM_VOLUME");
        assertThat(momentum.get("version").asText()).isEqualTo("v1");
        JsonNode window = momentum.get("parameters").get(0);
        assertThat(window.get("key").asText()).isEqualTo("windowMinutes");
        assertThat(window.get("default").isTextual()).isTrue();
        assertThat(window.get("min").asText()).isEqualTo("10");
        assertThat(window.get("step").asText()).isEqualTo("5");
    }

    /** US4.1 to US4.3, AC4.4.1: the whole wizard over HTTP; the draft is read back with every saved step. */
    @Test
    void theWizardCreatesSavesValidatesAndStartsTheBot() throws Exception {
        Guest guest = signIn();
        TokenId token = newToken();

        MockHttpServletResponse created =
                call(guest, json(post("/api/v1/bots"), "{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"Meu bot\"}"));
        assertThat(created.getStatus()).isEqualTo(201);
        String botId = body(created).get("botId").asText();
        assertThat(body(created).get("state").asText()).isEqualTo("DRAFT");
        assertThat(body(created).get("mode").asText()).isEqualTo("PAPER");

        assertThat(call(guest, json(put("/api/v1/bots/" + botId + "/draft"), stepOne(token)))
                        .getStatus())
                .isEqualTo(200);
        assertThat(call(guest, json(put("/api/v1/bots/" + botId + "/draft"), stepTwo("1000.000000", "200.000000")))
                        .getStatus())
                .isEqualTo(200);
        JsonNode draft = body(call(guest, get("/api/v1/bots/" + botId)));
        assertThat(draft.get("draftStep").asInt()).isEqualTo(2);
        assertThat(draft.get("virtualBalance").asText()).isEqualTo("1000.000000");
        assertThat(draft.get("tokenSelection").get("tokens").get(0).get("mint").asText())
                .isEqualTo(token.mint());
        assertThat(draft.get("limits").get("maxDrawdown").asText()).isEqualTo("10.0000");

        JsonNode validated = body(call(guest, json(put("/api/v1/bots/" + botId + "/draft"), "{\"step\":3}")));
        assertThat(validated.get("state").asText()).isEqualTo("VALIDATED");
        assertThat(validated.get("limitsVersion").asInt()).isEqualTo(1);

        JsonNode started = body(call(guest, idem(post("/api/v1/bots/" + botId + "/commands/start"))));
        assertThat(started.get("state").asText()).isEqualTo("ACTIVE");
        JsonNode list = body(call(guest, get("/api/v1/bots")));
        assertThat(list.get("activeCount").asInt()).isEqualTo(1);
        assertThat(list.get("activeLimit").asInt()).isEqualTo(3);
        assertThat(list.get("items").get(0).get("botId").asText()).isEqualTo(botId);
    }

    /** US4.8, US4.12: pause, resume and stop over HTTP, each with its state in the answer. */
    @Test
    void pauseResumeAndStopAnswerTheConfirmedState() throws Exception {
        Guest guest = signIn();
        String botId = activeBot(guest);

        JsonNode paused = body(call(guest, idem(post("/api/v1/bots/" + botId + "/commands/pause"))));
        assertThat(paused.get("state").asText()).isEqualTo("PAUSED");
        assertThat(paused.get("pauseReasons").get(0).asText()).isEqualTo("PERSON");
        assertThat(body(call(guest, idem(post("/api/v1/bots/" + botId + "/commands/resume"))))
                        .get("state")
                        .asText())
                .isEqualTo("ACTIVE");
        assertThat(body(call(guest, idem(post("/api/v1/bots/" + botId + "/commands/stop"))))
                        .get("state")
                        .asText())
                .isEqualTo("STOPPED");
    }

    /** NFR5.1, BR2.10: another person of the same tenant and another tenant get 404 on every route of the bot. */
    @Test
    void anotherPersonsBotIsNotFoundOnEveryRoute() throws Exception {
        Guest owner = signIn();
        String botId = activeBot(owner);
        Guest sameTenant = signInSameTenant(owner);
        Guest stranger = signIn();

        for (Guest intruder : List.of(sameTenant, stranger)) {
            for (MockHttpServletRequestBuilder request : List.of(
                    get("/api/v1/bots/" + botId),
                    get("/api/v1/bots/" + botId + "/decisions"),
                    json(put("/api/v1/bots/" + botId + "/draft"), "{\"step\":3}"),
                    idem(post("/api/v1/bots/" + botId + "/commands/pause")),
                    json(post("/api/v1/bots/" + botId + "/limits"), limits("100.000000", "200.000000")))) {
                MockHttpServletResponse response = call(intruder, request);
                assertThat(response.getStatus()).isEqualTo(404);
                assertThat(response.getContentAsString()).doesNotContain(botId);
            }
            assertThat(body(call(intruder, get("/api/v1/bots"))).get("items")).isEmpty();
        }
        assertThat(body(call(owner, get("/api/v1/bots/" + botId))).get("state").asText())
                .isEqualTo("ACTIVE");
    }

    /** NFR5.3, BR2.9, AC4.2.5: a mode other than PAPER is 403 REAL_MODE_UNAVAILABLE, audited; nothing changes. */
    @Test
    void anotherModeIsForbiddenAndAudited() throws Exception {
        Guest guest = signIn();
        long before = OutboxEvents.payloads(jdbc, "REQUEST_FORBIDDEN").size();

        MockHttpServletResponse body = call(
                guest,
                json(post("/api/v1/bots"), "{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"x\",\"mode\":\"REAL\"}"));
        MockHttpServletResponse query = call(guest, get("/api/v1/bots?mode=REAL"));

        assertThat(body.getStatus()).isEqualTo(403);
        assertThat(problem(body).get("code").asText()).isEqualTo("REAL_MODE_UNAVAILABLE");
        assertThat(query.getStatus()).isEqualTo(403);
        assertThat(OutboxEvents.payloads(jdbc, "REQUEST_FORBIDDEN").size()).isGreaterThanOrEqualTo((int) before + 2);
        assertThat(body(call(guest, get("/api/v1/bots"))).get("items")).isEmpty();
    }

    /** NFR6.2, AC4.1.2, AC4.2.3, AC4.2.4: each invalid field is 422 with its field; an unknown field is 400. */
    @Test
    void invalidStepsAre422PerFieldAndUnknownFieldsAre400() throws Exception {
        Guest guest = signIn();
        String botId = draft(guest);

        MockHttpServletResponse window = call(
                guest,
                json(
                        put("/api/v1/bots/" + botId + "/draft"),
                        stepOne(newToken()).replace("\"windowMinutes\":\"15\"", "\"windowMinutes\":\"65\"")));
        MockHttpServletResponse incoherent =
                call(guest, json(put("/api/v1/bots/" + botId + "/draft"), stepTwo("1000", "600.000000")));
        MockHttpServletResponse cap =
                call(guest, json(put("/api/v1/bots/" + botId + "/draft"), stepTwo("10000.000001", "200")));
        MockHttpServletResponse unknown =
                call(guest, json(put("/api/v1/bots/" + botId + "/draft"), "{\"step\":1,\"leverage\":\"2\"}"));

        assertThat(window.getStatus()).isEqualTo(422);
        assertThat(problem(window).get("fields").get(0).get("field").asText()).isEqualTo("parameters.windowMinutes");
        assertThat(incoherent.getStatus()).isEqualTo(422);
        assertThat(problem(incoherent).get("code").asText()).isEqualTo("LIMIT_INCOHERENT");
        assertThat(cap.getStatus()).isEqualTo(422);
        assertThat(problem(cap).get("code").asText()).isEqualTo("VIRTUAL_BALANCE_ABOVE_CAP");
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(problem(unknown).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    /** BR2.1, BR2.2: a refused transition and the fourth active bot are 409 with their codes; nothing changes. */
    @Test
    void refusedTransitionsAre409WithTheirCodes() throws Exception {
        Guest guest = signIn();
        String active = activeBot(guest);
        activeBot(guest);
        activeBot(guest);
        String fourth = validatedBot(guest);

        MockHttpServletResponse resumeActive = call(guest, idem(post("/api/v1/bots/" + active + "/commands/resume")));
        MockHttpServletResponse startFourth = call(guest, idem(post("/api/v1/bots/" + fourth + "/commands/start")));
        MockHttpServletResponse unknownCommand =
                call(guest, idem(post("/api/v1/bots/" + active + "/commands/transfer")));

        assertThat(resumeActive.getStatus()).isEqualTo(409);
        assertThat(problem(resumeActive).get("code").asText()).isEqualTo("INVALID_TRANSITION");
        assertThat(startFourth.getStatus()).isEqualTo(409);
        assertThat(problem(startFourth).get("code").asText()).isEqualTo("ACTIVE_BOT_LIMIT");
        assertThat(unknownCommand.getStatus()).isEqualTo(400);
        assertThat(body(call(guest, get("/api/v1/bots/" + fourth))).get("state").asText())
                .isEqualTo("VALIDATED");
    }

    /** C4 common rules: the Idempotency-Key is required; the same key with another body is 409 IDEMPOTENCY_CONFLICT. */
    @Test
    void theIdempotencyKeyIsRequiredAndBoundToTheContent() throws Exception {
        Guest guest = signIn();
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse missing = call(
                guest,
                post("/api/v1/bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"a\"}"));
        MockHttpServletResponse first = call(
                guest,
                post("/api/v1/bots")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"a\"}"));
        MockHttpServletResponse replay = call(
                guest,
                post("/api/v1/bots")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"a\"}"));
        MockHttpServletResponse other = call(
                guest,
                post("/api/v1/bots")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"b\"}"));

        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(body(replay).get("botId")).isEqualTo(body(first).get("botId"));
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(body(call(guest, get("/api/v1/bots"))).get("items")).hasSize(1);
    }

    /** BR5.1, US4.9: the new limits version answers 201 with the version; incoherent limits are 422 on the field. */
    @Test
    void theLimitsGetANewVersion() throws Exception {
        Guest guest = signIn();
        String botId = activeBot(guest);

        MockHttpServletResponse changed =
                call(guest, json(post("/api/v1/bots/" + botId + "/limits"), limits("100.000000", "300.000000")));
        MockHttpServletResponse incoherent =
                call(guest, json(post("/api/v1/bots/" + botId + "/limits"), limits("400.000000", "300.000000")));

        assertThat(changed.getStatus()).isEqualTo(201);
        assertThat(body(changed).get("limitsVersion").asInt()).isEqualTo(2);
        assertThat(incoherent.getStatus()).isEqualTo(422);
        assertThat(problem(incoherent).get("fields").get(0).get("field").asText())
                .isEqualTo("maxOrderSize");
    }

    /** US4.5, C4: the decisions of the bot, newest first, by cursor, decimals as text and UNKNOWN kept. */
    @Test
    void theDecisionsArePagedNewestFirstWithDecimalsAsText() throws Exception {
        Guest guest = signIn();
        String botId = activeBot(guest);
        for (int i = 0; i < 3; i++) {
            decision(
                    UUID.fromString(botId), Instant.parse("2026-10-28T12:0" + i + ":00Z"), i == 2 ? "BUY" : "NO_TRADE");
        }

        JsonNode first = body(call(guest, get("/api/v1/bots/" + botId + "/decisions?limit=2")));
        JsonNode second = body(call(
                guest,
                get("/api/v1/bots/" + botId + "/decisions?limit=2&cursor="
                        + first.get("nextCursor").asText())));

        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("items").get(0).get("action").asText()).isEqualTo("BUY");
        assertThat(first.get("items").get(0).get("requestedQuantity").isTextual())
                .isTrue();
        assertThat(first.get("items").get(1).get("noTradeReason").asText()).isEqualTo("NO_SIGNAL");
        assertThat(first.get("items")
                        .get(1)
                        .get("factors")
                        .get(0)
                        .get("value")
                        .get("status")
                        .asText())
                .isEqualTo("UNKNOWN");
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(call(guest, get("/api/v1/bots/" + botId + "/decisions?cursor=not-a-cursor"))
                        .getStatus())
                .isEqualTo(400);
    }

    /** NFR10.7: a bot lock held longer than 2 s makes the command answer 503 "tentar de novo". */
    @Test
    void aLockHeldTooLongIs503() throws Exception {
        Guest guest = signIn();
        String botId = activeBot(guest);
        try (Connection holder = DriverManager.getConnection(U5Sql.url(), U5Sql.APP_USER, U5Sql.APP_PASSWORD)) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("select 1 from bot where bot_id = '" + botId + "' for update");

            MockHttpServletResponse response = call(guest, idem(post("/api/v1/bots/" + botId + "/commands/pause")));

            assertThat(response.getStatus()).isEqualTo(503);
            holder.rollback();
        }
    }

    @Test
    void everyRouteRequiresASession() throws Exception {
        assertThat(mvc.perform(get("/api/v1/bots")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/strategy-templates"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    // ----- helpers -----

    private String draft(Guest guest) throws Exception {
        return body(call(guest, json(post("/api/v1/bots"), "{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"Bot\"}")))
                .get("botId")
                .asText();
    }

    private String validatedBot(Guest guest) throws Exception {
        String botId = draft(guest);
        call(guest, json(put("/api/v1/bots/" + botId + "/draft"), stepOne(newToken())));
        call(guest, json(put("/api/v1/bots/" + botId + "/draft"), stepTwo("1000.000000", "200.000000")));
        call(guest, json(put("/api/v1/bots/" + botId + "/draft"), "{\"step\":3}"));
        return botId;
    }

    private String activeBot(Guest guest) throws Exception {
        String botId = validatedBot(guest);
        MockHttpServletResponse started = call(guest, idem(post("/api/v1/bots/" + botId + "/commands/start")));
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(200);
        return botId;
    }

    private static String stepOne(TokenId token) {
        return """
                {"step":1,"parameters":{"windowMinutes":"15","volumeGrowthMinPct":"20.0000","priceRiseMinPct":"2.0000",
                "takeProfitPct":"10.0000","stopLossPct":"5.0000","maxHoldMinutes":"240","buyFractionPct":"10.0000"},
                "tokenSelection":{"tokens":[{"chain":"solana","mint":"%s"}],"rules":[]}}
                """.formatted(token.mint());
    }

    private static String stepTwo(String balance, String tokenExposure) {
        return """
                {"step":2,"virtualBalance":"%s","limits":%s}
                """.formatted(balance, limits("100.000000", tokenExposure));
    }

    private static String limits(String order, String tokenExposure) {
        return """
                {"maxOrderSize":"%s","maxTokenExposure":"%s","maxTotalExposure":"500.000000","maxDailyLoss":"50.000000",
                "maxDrawdown":"10.0000","maxSlippage":"2.0000","minLiquidity":"10000.000000","minIntervalSeconds":240}
                """.formatted(order, tokenExposure);
    }

    private void decision(UUID botId, Instant at, String action) {
        UUID cycle = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot_cycle (cycle_id, bot_id, tenant_id, owner_user_id, slot_start, status,
                                               started_at, finished_at)
                        select :cycle, bot_id, tenant_id, owner_user_id, :at, 'COMPLETED', :at, :at
                        from bot where bot_id = :bot
                        """)
                .param("cycle", cycle)
                .param("bot", botId)
                .param("at", Timestamp.from(at))
                .update();
        boolean buy = action.equals("BUY");
        jdbc.sql("""
                        insert into decision (decision_id, bot_id, cycle_id, tenant_id, owner_user_id, mode, chain,
                            token_mint, action, no_trade_reason, requested_quantity, strategy_template_id,
                            strategy_version, parameters, limits_version, factors, inputs, context, summary,
                            valid_until, created_at)
                        select :id, bot_id, :cycle, tenant_id, owner_user_id, 'PAPER', 'solana', 'MintRoute', :action,
                               :reason, :quantity, 'MOMENTUM_VOLUME', 'v1', '{}'::jsonb, 1,
                               '[{"token":{"chain":"solana","mint":"MintRoute"},"name":"priceRisePct",
                                  "value":{"status":"UNKNOWN"},"threshold":"2.0000","satisfied":false}]'::jsonb,
                               '[]'::jsonb, '{}'::jsonb, 'Teste', :at, :at
                        from bot where bot_id = :bot
                        """)
                .param("id", UUID.randomUUID())
                .param("cycle", cycle)
                .param("bot", botId)
                .param("action", action)
                .param("reason", buy ? null : "NO_SIGNAL")
                .param("quantity", buy ? new java.math.BigDecimal("98.039215") : null)
                .param("at", Timestamp.from(at))
                .update();
    }

    private Guest signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        return new Guest(
                "Bearer " + auth.accessToken(auth.login(wallet)),
                new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER));
    }

    /** A second guest in the SAME tenant as {@code other}, with its own wallet and session. */
    private Guest signInSameTenant(Guest other) throws Exception {
        TestWallet wallet = TestWallet.generate();
        UUID userId = UUID.randomUUID();
        jdbc.sql("insert into app_user (user_id, tenant_id, role, status, created_at)"
                        + " values (:id, :tenant, 'BETA_USER', 'ACTIVE', now())")
                .param("id", userId)
                .param("tenant", other.principal().tenantId())
                .update();
        jdbc.sql("insert into wallet (wallet_id, user_id, address, provider, verified_at)"
                        + " values (:id, :user, :address, 'PHANTOM', now())")
                .param("id", UUID.randomUUID())
                .param("address", wallet.address())
                .param("user", userId)
                .update();
        AuthFlow auth = new AuthFlow(mvc, json);
        return new Guest(
                "Bearer " + auth.accessToken(auth.login(wallet)),
                new Principal(other.principal().tenantId(), userId, Role.BETA_USER));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return idem(request).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder idem(MockHttpServletRequestBuilder request) {
        return request.header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private MockHttpServletResponse call(Guest guest, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", guest.bearer()))
                .andReturn()
                .getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 299);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode problem(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }
}
