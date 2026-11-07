package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU9MeasurementIT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.CycleAuthContext;
import com.memetrade.testsupport.PaperDriver;
import com.memetrade.testsupport.U5Bots;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 11 of U9 (NFR1.1, AC5.4.1, performance-design.md "Como as metas são medidas"): 20 guests keep the live channel
 * open over the real port with JDK HTTP clients while the single reader runs every 2 s, as in production. Changes of
 * state go through the command route (C4 {@code pause}) and orders through the PAPER execution of U7 (the same path
 * the cycle and the loadtest drivers use). The delay is the time the client receives the event minus the time of the
 * write: p95 of at most 5 s and never above 60 s. Runs in the full suite of the merge to main.
 */
class DashboardStreamDelayIT extends AbstractU9MeasurementIT {

    private static final int SESSIONS = 20;
    private static final Duration P95_LIMIT = Duration.ofSeconds(5);
    private static final Duration MAX_LIMIT = Duration.ofSeconds(60);

    @Autowired
    private BotCommandService commands;

    @Autowired
    private JdbcBotRepository botRepository;

    @Autowired
    private PaperExecution execution;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DashboardMarkPoller poller;

    /** One line of a stream with the instant (nanoTime) the client read it. */
    private record Received(long at, String line) {}

    private record Watched(Session session, UUID botId, TokenId token, ConcurrentLinkedQueue<Received> lines) {}

    @Test
    void aChangeReachesTheClientWithinFiveSecondsAtP95AndNeverAboveSixtySeconds() throws Exception {
        U5Bots fixtures = new U5Bots(commands, botRepository, CycleAuthContext::as);
        PaperDriver driver = new PaperDriver(execution, transactions);
        HttpClient http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        List<Watched> watched = new ArrayList<>();
        List<InputStream> streams = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            Session session = newSession();
            TokenId token = newToken();
            market.values().set(token, "1.000000", "1000000.000000", 6);
            UUID botId = fixtures.active(session.person(), token);
            ConcurrentLinkedQueue<Received> lines = new ConcurrentLinkedQueue<>();
            streams.add(listen(http, session, lines));
            watched.add(new Watched(session, botId, token, lines));
        }
        Thread reader = Thread.ofVirtual().start(() -> {
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
            List<Duration> delays = new ArrayList<>();
            delays.addAll(measure(
                    watched,
                    w -> {
                        pause(http, w);
                        return "\"" + w.botId() + "\"";
                    },
                    "bot.state",
                    "PAUSED"));
            // Orders are recorded one second later on the app clock, after the opening instant of every channel.
            clock.advance(Duration.ofSeconds(1));
            delays.addAll(measure(
                    watched,
                    w -> {
                        driver.buy(w.session().person(), w.botId(), w.token(), "10");
                        return "\"" + w.botId() + "\"";
                    },
                    "trade.recorded",
                    null));

            List<Duration> sorted = delays.stream().sorted().toList();
            Duration p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
            Duration max = sorted.get(sorted.size() - 1);
            LoggerFactory.getLogger(DashboardStreamDelayIT.class)
                    .info(
                            "U9 live channel delay: changes={}, p95Ms={}, maxMs={}",
                            sorted.size(),
                            p95.toMillis(),
                            max.toMillis());
            assertThat(sorted).hasSize(SESSIONS * 2);
            assertThat(p95).isLessThanOrEqualTo(P95_LIMIT);
            assertThat(max).isLessThanOrEqualTo(MAX_LIMIT);
        } finally {
            reader.interrupt();
            for (InputStream stream : streams) {
                stream.close();
            }
            // Past the token expiry the reader ends every channel on the server too.
            clock.advance(Duration.ofMinutes(16));
            poller.pollOnce();
        }
    }

    @FunctionalInterface
    private interface Change {
        /** Writes the change of one session and returns the text its event must carry. */
        String write(Watched watched) throws Exception;
    }

    /** Writes one change per session at the same time and waits for each event; the delays in write order. */
    private List<Duration> measure(List<Watched> watched, Change change, String event, String mustContain) {
        List<Callable<Duration>> tasks = new ArrayList<>();
        for (Watched w : watched) {
            tasks.add(() -> {
                int seen = w.lines().size();
                long written = System.nanoTime();
                String marker = change.write(w);
                return awaitEvent(w, seen, written, event, marker, mustContain);
            });
        }
        @SuppressWarnings("unchecked")
        List<Concurrently.Outcome<Duration>> outcomes = Concurrently.run(tasks.toArray(new Callable[0]));
        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        return outcomes.stream().map(Concurrently.Outcome::value).toList();
    }

    /** The delay until a block of {@code event} that names {@code marker} (and carries {@code mustContain}) arrives. */
    private static Duration awaitEvent(
            Watched w, int seen, long written, String event, String marker, String mustContain) throws Exception {
        long deadline = written + MAX_LIMIT.plusSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<Received> lines = List.copyOf(w.lines());
            for (int i = Math.min(seen, lines.size()); i + 1 < lines.size(); i++) {
                if (lines.get(i).line().equals("event:" + event)) {
                    Received data = lines.get(i + 1);
                    if (data.line().startsWith("data:")
                            && data.line().contains(marker)
                            && (mustContain == null || data.line().contains(mustContain))
                            && data.at() >= written) {
                        return Duration.ofNanos(data.at() - written);
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("no " + event + " event within " + MAX_LIMIT.plusSeconds(5));
    }

    private void pause(HttpClient http, Watched w) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/api/v1/bots/" + w.botId() + "/commands/pause"))
                        .header("Authorization", w.session().bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode bot = json.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(bot.get("state").asText()).isEqualTo("PAUSED");
    }

    /** Opens the live channel of {@code session} and reads every line on a virtual thread, with its arrival time. */
    private InputStream listen(HttpClient http, Session session, ConcurrentLinkedQueue<Received> lines)
            throws Exception {
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stream/dashboard"))
                        .header("Authorization", session.bearer())
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        InputStream body = response.body();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    lines.add(new Received(System.nanoTime(), line));
                }
            } catch (java.io.IOException closed) {
                // The test closed the stream at the end; nothing more to read.
                lines.add(new Received(System.nanoTime(), ":closed"));
            }
        });
        return body;
    }
}
