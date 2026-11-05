package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Passo 14 of U5, WRITTEN BEFORE THE CHANNEL (C7; BR8.2; NFR3.3, NFR5.1): the live channel of the dashboard sends the
 * state of the person's bots when the mark changes, a heartbeat every 15 s, auth.expired when the token expires, resumes
 * from Last-Event-ID without repeating, never sends anything of another person, and shows a change within 4 s.
 */
class DashboardStreamIT extends AbstractU5IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DashboardMarkPoller poller;

    @Test
    void theChannelOpensWithTheStateOfThePersonsBots() throws Exception {
        String guest = signIn();
        String botId = draft(guest);

        MvcResult stream = open(guest, null);

        assertThat(content(stream)).contains("event:bot.state").contains(botId);
        assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void aChangeOfStateIsSentAfterTheMarkChanges() throws Exception {
        String guest = signIn();
        String botId = activeBot(guest);
        MvcResult stream = open(guest, null);
        int before = content(stream).length();

        command(guest, botId, "pause");
        poller.pollOnce();

        assertThat(content(stream).substring(before))
                .contains("event:bot.state")
                .contains("PAUSED");
    }

    @Test
    void aHeartbeatIsSentEveryFifteenSeconds() throws Exception {
        String guest = signIn();
        MvcResult stream = open(guest, null);

        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();

        assertThat(content(stream)).contains("event:heartbeat");
    }

    @Test
    void anExpiredTokenClosesTheChannelWithAuthExpired() throws Exception {
        String guest = signIn();
        MvcResult stream = open(guest, null);

        clock.advance(Duration.ofMinutes(16));
        poller.pollOnce();

        assertThat(content(stream)).contains("event:auth.expired");
        assertThat(poller.openChannels()).isZero();
    }

    /** Last-Event-ID: reconnecting with the last id seen sends no state again until something changes. */
    @Test
    void reconnectingWithTheLastEventIdDoesNotRepeat() throws Exception {
        String guest = signIn();
        String botId = draft(guest);
        MvcResult first = open(guest, null);
        String lastId = lastEventId(content(first));

        MvcResult resumed = open(guest, lastId);

        assertThat(content(resumed)).doesNotContain("event:bot.state");
        command(guest, botId, null);
        poller.pollOnce();
        assertThat(content(resumed)).contains("event:bot.state");
    }

    /** NFR5.1: a change of another person never reaches this channel. */
    @Test
    void aChannelNeverReceivesAnEventOfAnotherPerson() throws Exception {
        String first = signIn();
        String second = signIn();
        draft(first);
        String othersBot = activeBot(second);
        MvcResult stream = open(first, null);

        command(second, othersBot, "pause");
        poller.pollOnce();
        clock.advance(Duration.ofSeconds(61));
        poller.pollOnce();

        assertThat(content(stream)).doesNotContain(othersBot);
    }

    /** NFR3.3: with the real poller every 2 s, a committed change appears in the channel within 4 s. */
    @Test
    void aChangeAppearsWithinFourSeconds() throws Exception {
        String guest = signIn();
        String botId = activeBot(guest);
        MvcResult stream = open(guest, null);
        int before = content(stream).length();
        Thread polling = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                poller.pollOnce();
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        });
        try {
            long committed = System.nanoTime();
            command(guest, botId, "pause");
            long seen = 0;
            while (System.nanoTime() - committed < TimeUnit.SECONDS.toNanos(6)) {
                if (content(stream).substring(before).contains("PAUSED")) {
                    seen = System.nanoTime();
                    break;
                }
                Thread.onSpinWait();
            }
            assertThat(seen).isPositive();
            assertThat(Duration.ofNanos(seen - committed)).isLessThanOrEqualTo(Duration.ofSeconds(4));
        } finally {
            polling.interrupt();
        }
    }

    @Test
    void theChannelRequiresASession() throws Exception {
        assertThat(mvc.perform(get("/api/v1/stream/dashboard"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    // ----- helpers -----

    private MvcResult open(String bearer, String lastEventId) throws Exception {
        var request =
                get("/api/v1/stream/dashboard").header("Authorization", bearer).accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request = request.header("Last-Event-ID", lastEventId);
        }
        return mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
    }

    private static String content(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private static String lastEventId(String content) {
        String last = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("id:")) {
                last = line.substring(3).trim();
            }
        }
        return last;
    }

    private String signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        return "Bearer " + auth.accessToken(auth.login(wallet));
    }

    private String draft(String bearer) throws Exception {
        MockHttpServletResponse created = mvc.perform(post("/api/v1/bots")
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"Canal\"}"))
                .andReturn()
                .getResponse();
        return json.readTree(created.getContentAsString()).get("botId").asText();
    }

    private String activeBot(String bearer) throws Exception {
        String botId = draft(bearer);
        TokenId token = newToken();
        saveStep(bearer, botId, """
                {"step":1,"parameters":{"windowMinutes":"15","volumeGrowthMinPct":"20.0000","priceRiseMinPct":"2.0000",
                "takeProfitPct":"10.0000","stopLossPct":"5.0000","maxHoldMinutes":"240","buyFractionPct":"10.0000"},
                "tokenSelection":{"tokens":[{"chain":"solana","mint":"%s"}],"rules":[]}}
                """.formatted(token.mint()));
        saveStep(bearer, botId, """
                {"step":2,"virtualBalance":"1000.000000","limits":{"maxOrderSize":"100.000000",
                "maxTokenExposure":"200.000000","maxTotalExposure":"500.000000","maxDailyLoss":"50.000000",
                "maxDrawdown":"10.0000","maxSlippage":"2.0000","minLiquidity":"10000.000000","minIntervalSeconds":240}}
                """);
        saveStep(bearer, botId, "{\"step\":3}");
        command(bearer, botId, "start");
        return botId;
    }

    private void saveStep(String bearer, String botId, String body) throws Exception {
        mvc.perform(put("/api/v1/bots/" + botId + "/draft")
                .header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** A command; {@code null} edits the draft (step 1 again), which also renews the mark. */
    private void command(String bearer, String botId, String command) throws Exception {
        if (command == null) {
            saveStep(bearer, botId, """
                    {"step":1,"parameters":{"windowMinutes":"20","volumeGrowthMinPct":"20.0000","priceRiseMinPct":"2.0000",
                    "takeProfitPct":"10.0000","stopLossPct":"5.0000","maxHoldMinutes":"240","buyFractionPct":"10.0000"},
                    "tokenSelection":{"tokens":[],"rules":[{"field":"LIQUIDITY","operator":"GTE","value":"1"}]}}
                    """);
            return;
        }
        mvc.perform(post("/api/v1/bots/" + botId + "/commands/" + command)
                .header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }
}
