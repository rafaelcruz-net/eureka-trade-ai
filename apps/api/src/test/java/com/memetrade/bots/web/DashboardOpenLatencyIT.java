package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.testsupport.AbstractU9MeasurementIT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.CycleAuthContext;
import com.memetrade.testsupport.U5Bots;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 of U9 (NFR3.1, performance-design.md "Como as metas são medidas"): in the reference load profile (20
 * guests, each with 3 ACTIVE and 20 STOPPED bots), 20 JDK HTTP clients open the dashboard at the same time over the
 * real port, each group of calls exactly as the app makes it (bots: the six lists by state with limit=100 following
 * nextCursor, then the last order of every bot that is not a draft, 3 at a time; P&L: P&L of the UTC day and
 * reconciliation in parallel; sources: the scanner list of one row; shell: the account; and the live channel, open on
 * its 200 answer). Every group reaches its answer with p95 of at most 2 s. Runs in the full suite of the merge to main.
 */
class DashboardOpenLatencyIT extends AbstractU9MeasurementIT {

    private static final int SESSIONS = 20;
    private static final int ACTIVE_BOTS = 3;
    private static final int STOPPED_BOTS = 20;
    private static final Duration P95_LIMIT = Duration.ofSeconds(2);
    private static final List<String> STATES =
            List.of("ACTIVE", "PAUSED", "CLOSING_PENDING", "DRAFT", "VALIDATED", "STOPPED");

    enum Group {
        BOTS,
        PNL,
        SOURCES,
        SHELL,
        CHANNEL
    }

    @Autowired
    private BotCommandService commands;

    @Autowired
    private JdbcBotRepository botRepository;

    @Autowired
    private DashboardMarkPoller poller;

    private HttpClient http;

    @Test
    void everyGroupOfTheDashboardOpeningAnswersWithinTwoSecondsAtP95() throws Exception {
        U5Bots fixtures = new U5Bots(commands, botRepository, CycleAuthContext::as);
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            Session session = newSession();
            for (int stopped = 0; stopped < STOPPED_BOTS; stopped++) {
                UUID botId = fixtures.active(session.person(), newToken());
                fixtures.as(session.person(), () -> commands.stop(session.person(), botId, U5Bots.key()));
            }
            for (int active = 0; active < ACTIVE_BOTS; active++) {
                fixtures.active(session.person(), newToken());
            }
            sessions.add(session);
        }
        refreshPlannerStatistics();
        http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        open(sessions.get(0));

        List<Callable<Map<Group, Duration>>> openings = new ArrayList<>();
        for (Session session : sessions) {
            openings.add(() -> open(session));
        }
        @SuppressWarnings("unchecked")
        List<Concurrently.Outcome<Map<Group, Duration>>> outcomes = Concurrently.run(openings.toArray(new Callable[0]));
        // The clients closed their channels; past the token expiry the reader ends them on the server too.
        clock.advance(Duration.ofMinutes(16));
        poller.pollOnce();

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        for (Group group : Group.values()) {
            List<Duration> timings = outcomes.stream()
                    .map(outcome -> outcome.value().get(group))
                    .sorted()
                    .toList();
            Duration p95 = timings.get((int) Math.ceil(SESSIONS * 0.95) - 1);
            LoggerFactory.getLogger(DashboardOpenLatencyIT.class)
                    .info("U9 dashboard opening: group={}, p95Ms={}", group, p95.toMillis());
            assertThat(p95).as(group.name()).isLessThanOrEqualTo(P95_LIMIT);
        }
    }

    /** One opening of the dashboard by one person: the five groups at the same time, each timed alone. */
    private Map<Group, Duration> open(Session session) throws Exception {
        long started = System.nanoTime();
        CompletableFuture<Duration> bots = async(() -> timed(started, () -> bots(session)));
        CompletableFuture<Duration> pnl = async(() -> timed(started, () -> pnl(session)));
        CompletableFuture<Duration> sources =
                async(() -> timed(started, () -> get(session, "/api/v1/scanner/tokens?limit=1")));
        CompletableFuture<Duration> shell = async(() -> timed(started, () -> get(session, "/api/v1/account")));
        CompletableFuture<Duration> channel = async(() -> timed(started, () -> channel(session)));
        Map<Group, Duration> timings = new EnumMap<>(Group.class);
        timings.put(Group.BOTS, bots.get());
        timings.put(Group.PNL, pnl.get());
        timings.put(Group.SOURCES, sources.get());
        timings.put(Group.SHELL, shell.get());
        timings.put(Group.CHANNEL, channel.get());
        return timings;
    }

    private void bots(Session session) throws Exception {
        List<CompletableFuture<List<String>>> lists = new ArrayList<>();
        for (String state : STATES) {
            lists.add(async(() -> listState(session, state)));
        }
        List<String> needLastOrder = new ArrayList<>();
        for (int i = 0; i < STATES.size(); i++) {
            List<String> ids = lists.get(i).get();
            if (!List.of("DRAFT", "VALIDATED").contains(STATES.get(i))) {
                needLastOrder.addAll(ids);
            }
        }
        assertThat(needLastOrder).hasSize(ACTIVE_BOTS + STOPPED_BOTS);
        Semaphore three = new Semaphore(3);
        List<CompletableFuture<String>> trades = new ArrayList<>();
        for (String botId : needLastOrder) {
            trades.add(async(() -> {
                three.acquire();
                try {
                    return get(session, "/api/v1/bots/" + botId + "/trades?limit=1");
                } finally {
                    three.release();
                }
            }));
        }
        CompletableFuture.allOf(trades.toArray(CompletableFuture[]::new)).get();
    }

    private List<String> listState(Session session, String state) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            String path = "/api/v1/bots?state=" + state + "&limit=100"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode page = json.readTree(get(session, path));
            page.get("items").forEach(item -> ids.add(item.get("botId").asText()));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asText();
        } while (cursor != null);
        return ids;
    }

    private void pnl(Session session) throws Exception {
        Instant now = clock.instant();
        String from = URLEncoder.encode(now.truncatedTo(ChronoUnit.DAYS).toString(), StandardCharsets.UTF_8);
        String to = URLEncoder.encode(now.toString(), StandardCharsets.UTF_8);
        CompletableFuture<String> report = async(() -> get(session, "/api/v1/pnl?from=" + from + "&to=" + to));
        CompletableFuture<String> reconciliation = async(() -> get(session, "/api/v1/reconciliation"));
        report.get();
        reconciliation.get();
    }

    /** The live channel counts as open on its 200 answer (BR5.3 of U9); the test closes it right after. */
    private void channel(Session session) throws Exception {
        HttpResponse<InputStream> response = http.send(
                request(session, "/api/v1/stream/dashboard")
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    private String get(Session session, String path) throws Exception {
        HttpResponse<String> response = http.send(
                request(session, path).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(path.split("\\?")[0] + " answered " + response.statusCode());
        }
        return response.body();
    }

    private HttpRequest.Builder request(Session session, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", session.bearer())
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .GET();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    /** Every call runs on its own virtual thread, as concurrent as the browser makes them. */
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    private <T> CompletableFuture<T> async(Work<T> work) {
        return CompletableFuture.supplyAsync(() -> unchecked(work), pool);
    }

    private static <T> T unchecked(Work<T> work) {
        try {
            return work.run();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The time from the start of the opening to the end of {@code work}. */
    private static Duration timed(long started, ThrowingRunnable work) {
        unchecked(() -> {
            work.run();
            return null;
        });
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
