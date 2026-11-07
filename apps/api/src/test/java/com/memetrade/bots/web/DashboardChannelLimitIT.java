package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractU9IT;
import com.memetrade.testsupport.Concurrently;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Passo 3 of U9 (NFR2.1, security-design.md "Teto de canais por pessoa"): a person holds at most 5 open live channels;
 * the 6th opening gets {@code 429 RATE_LIMITED} as a problem with {@code Retry-After} and {@code correlationId} and no
 * data of the person; concurrent openings of the same person never exceed the cap; every end of a channel
 * (completion, timeout, error, {@code auth.expired}) gives its slot back; another person is never affected.
 */
class DashboardChannelLimitIT extends AbstractU9IT {

    private static final int LIMIT = 5;

    @Autowired
    private DashboardMarkPoller poller;

    @Test
    void theSixthChannelOfTheSamePersonIsRefusedWith429AndRetryAfter() throws Exception {
        String guest = signIn();
        for (int i = 0; i < LIMIT; i++) {
            assertThat(open(guest).getRequest().isAsyncStarted()).isTrue();
        }

        MvcResult refused = open(guest);

        MockHttpServletResponse response = refused.getResponse();
        assertThat(refused.getRequest().isAsyncStarted()).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(problem.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void theRefusalCarriesNoDataOfThePerson() throws Exception {
        String guest = signIn();
        JsonNode account = json.readTree(mvc.perform(get("/api/v1/account").header("Authorization", guest))
                .andReturn()
                .getResponse()
                .getContentAsString());
        for (int i = 0; i < LIMIT; i++) {
            open(guest);
        }

        String body = open(guest).getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(guest.substring("Bearer ".length()))
                .doesNotContain(account.get("walletAddressMasked").asText())
                .doesNotContain("tenantId")
                .doesNotContain("userId")
                .doesNotContain("event:");
    }

    @Test
    void sixConcurrentOpeningsOfTheSamePersonLeaveAtMostFiveOpen() throws Exception {
        String guest = signIn();
        List<Callable<Integer>> openings = new ArrayList<>();
        for (int i = 0; i < LIMIT + 1; i++) {
            openings.add(() -> {
                MvcResult result = open(guest);
                return result.getRequest().isAsyncStarted()
                        ? 200
                        : result.getResponse().getStatus();
            });
        }

        @SuppressWarnings("unchecked")
        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(openings.toArray(new Callable[0]));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes.stream().filter(outcome -> outcome.value() == 200)).hasSize(LIMIT);
        assertThat(outcomes.stream().filter(outcome -> outcome.value() == 429)).hasSize(1);
        assertThat(open(guest).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void completingAChannelGivesItsSlotBack() throws Exception {
        String guest = signIn();
        List<MvcResult> streams = openAll(guest);
        assertThat(open(guest).getResponse().getStatus()).isEqualTo(429);

        asyncContext(streams.get(0)).complete();

        assertThat(open(guest).getRequest().isAsyncStarted()).isTrue();
        assertThat(open(guest).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void aTimeoutOrAnErrorOfTheConnectionGivesTheSlotBack() throws Exception {
        String guest = signIn();
        List<MvcResult> streams = openAll(guest);

        MockAsyncContext timedOut = asyncContext(streams.get(0));
        for (AsyncListener listener : timedOut.getListeners()) {
            listener.onTimeout(new AsyncEvent(timedOut));
        }
        MockAsyncContext broken = asyncContext(streams.get(1));
        for (AsyncListener listener : broken.getListeners()) {
            listener.onError(new AsyncEvent(broken, new IOException("connection reset by the browser")));
        }

        assertThat(open(guest).getRequest().isAsyncStarted()).isTrue();
        assertThat(open(guest).getRequest().isAsyncStarted()).isTrue();
        assertThat(open(guest).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void anExpiredTokenClosesTheChannelsAndGivesEverySlotBack() throws Exception {
        String guest = signIn();
        openAll(guest);

        clock.advance(Duration.ofMinutes(16));
        poller.pollOnce();
        clock.advance(Duration.ofMinutes(-16));

        assertThat(open(guest).getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void anotherPersonOpensNormallyWhileTheFirstIsAtTheCap() throws Exception {
        String first = signIn();
        String second = signIn();
        openAll(first);

        MvcResult others = open(second);

        assertThat(others.getRequest().isAsyncStarted()).isTrue();
        assertThat(others.getResponse().getContentAsString()).contains("event:bot.state");
        assertThat(open(first).getResponse().getStatus()).isEqualTo(429);
    }

    /**
     * Review R-03 (regression): a {@code Last-Event-ID} whose number is above {@code Long.MAX_VALUE} used to throw after
     * the slot was taken, so the slot leaked and the answer was 500. Now the channel opens without resumption, and the
     * slots are given back when the channels end.
     */
    @Test
    void anUnreadableLastEventIdNeverLeaksASlot() throws Exception {
        Session guest = newSession();
        String oversized = "b99999999999999999999.l1.s1";
        List<MvcResult> streams = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            MvcResult stream = open(guest.bearer(), oversized);
            assertThat(stream.getRequest().isAsyncStarted()).isTrue();
            assertThat(stream.getResponse().getContentAsString()).contains("event:bot.state");
            streams.add(stream);
        }
        assertThat(poller.openChannelsOf(guest.person())).isEqualTo(LIMIT);
        assertThat(open(guest.bearer(), oversized).getResponse().getStatus()).isEqualTo(429);

        for (MvcResult stream : streams) {
            asyncContext(stream).complete();
        }

        assertThat(poller.openChannelsOf(guest.person())).isZero();
        assertThat(open(guest.bearer(), oversized).getRequest().isAsyncStarted())
                .isTrue();
        assertThat(poller.openChannelsOf(guest.person())).isEqualTo(1);
    }

    // ----- helpers -----

    private MvcResult open(String bearer, String lastEventId) throws Exception {
        return mvc.perform(get("/api/v1/stream/dashboard")
                        .header("Authorization", bearer)
                        .header("Last-Event-ID", lastEventId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    private List<MvcResult> openAll(String bearer) throws Exception {
        List<MvcResult> streams = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            MvcResult stream = open(bearer);
            assertThat(stream.getRequest().isAsyncStarted()).isTrue();
            streams.add(stream);
        }
        return streams;
    }

    private MvcResult open(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/stream/dashboard")
                        .header("Authorization", bearer)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    private static MockAsyncContext asyncContext(MvcResult stream) {
        return (MockAsyncContext) stream.getRequest().getAsyncContext();
    }
}
