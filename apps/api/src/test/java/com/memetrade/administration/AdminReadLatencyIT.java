package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.Concurrently;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Passo 12 of U8 (NFR3.1, performance-design.md, Q5): 20 simultaneous reads of each administrative screen route
 * ({@code system-status}, {@code invites}, {@code kill-switch}, {@code feedback}) answer with p95 of at most 2 s.
 */
class AdminReadLatencyIT extends AbstractU8IT {

    private static final int SIMULTANEOUS = 20;
    private static final Duration P95_LIMIT = Duration.ofSeconds(2);

    @Test
    void twentySimultaneousReadsOfEachRouteAnswerWithinTwoSecondsAtP95() throws Exception {
        AdminSessions.AdminSession admin = sessionOf(newAdministrator());
        Guest guest = newGuest();
        for (int i = 0; i < 5; i++) {
            postFeedback(guest.bearer(), "S1", "Leitura " + i, UUID.randomUUID());
        }
        for (String route : List.of(
                "/api/v1/admin/system-status",
                "/api/v1/admin/invites",
                "/api/v1/admin/kill-switch",
                "/api/v1/admin/feedback")) {
            read(route, admin);
            List<Callable<Duration>> reads = new ArrayList<>();
            for (int i = 0; i < SIMULTANEOUS; i++) {
                reads.add(() -> {
                    long started = System.nanoTime();
                    int status = read(route, admin);
                    if (status != 200) {
                        throw new IllegalStateException(route + " answered " + status);
                    }
                    return Duration.ofNanos(System.nanoTime() - started);
                });
            }

            @SuppressWarnings("unchecked")
            List<Concurrently.Outcome<Duration>> outcomes = Concurrently.run(reads.toArray(new Callable[0]));

            assertThat(outcomes).as(route).noneMatch(Concurrently.Outcome::failed);
            List<Duration> timings =
                    outcomes.stream().map(Concurrently.Outcome::value).sorted().toList();
            Duration p95 = timings.get((int) Math.ceil(SIMULTANEOUS * 0.95) - 1);
            org.slf4j.LoggerFactory.getLogger(AdminReadLatencyIT.class)
                    .info("U8 admin read: route={}, p95Ms={}", route, p95.toMillis());
            assertThat(p95).as(route).isLessThanOrEqualTo(P95_LIMIT);
        }
    }

    private int read(String route, AdminSessions.AdminSession admin) throws Exception {
        return mvc.perform(get(route).header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
