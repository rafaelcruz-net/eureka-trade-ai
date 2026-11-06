package com.memetrade.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.Concurrently;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of U8, written BEFORE the feedback code from the rule (NFR3.3, performance-design.md): 20 different people
 * send a comment at the same instant; every one is accepted and the p95 of the answers is at most 500 ms (one person
 * lock and one indexed count per submission, so people never wait for each other).
 */
class FeedbackConcurrencyIT extends AbstractU8IT {

    private static final int PEOPLE = 20;
    private static final Duration P95_LIMIT = Duration.ofMillis(500);

    @Test
    void twentyPeopleAtTheSameTimeAnswerWithinFiveHundredMillisecondsAtP95() throws Exception {
        List<Guest> guests = new ArrayList<>();
        for (int i = 0; i < PEOPLE; i++) {
            guests.add(newGuest());
        }
        // Warm-up outside the measure (first use of the route in this context).
        postFeedback(guests.getFirst().bearer(), "S1", "Aquecimento.", UUID.randomUUID());
        List<Callable<Duration>> sends = new ArrayList<>();
        for (Guest guest : guests) {
            sends.add(() -> {
                long started = System.nanoTime();
                int status = postFeedback(guest.bearer(), "S3", "Envio de várias pessoas.", UUID.randomUUID())
                        .getStatus();
                if (status != 201) {
                    throw new IllegalStateException("status " + status);
                }
                return Duration.ofNanos(System.nanoTime() - started);
            });
        }

        @SuppressWarnings("unchecked")
        List<Concurrently.Outcome<Duration>> answers = Concurrently.run(sends.toArray(new Callable[0]));

        assertThat(answers).noneMatch(Concurrently.Outcome::failed);
        List<Duration> timings =
                answers.stream().map(Concurrently.Outcome::value).sorted().toList();
        Duration p95 = timings.get((int) Math.ceil(PEOPLE * 0.95) - 1);
        org.slf4j.LoggerFactory.getLogger(FeedbackConcurrencyIT.class)
                .info(
                        "U8 feedback with 20 people: p95Ms={}, maxMs={}",
                        p95.toMillis(),
                        timings.getLast().toMillis());
        assertThat(p95).as("p95 of 20 simultaneous people: %s", timings).isLessThanOrEqualTo(P95_LIMIT);
    }
}
