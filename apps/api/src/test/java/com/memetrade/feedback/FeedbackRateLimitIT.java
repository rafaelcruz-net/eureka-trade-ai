package com.memetrade.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.Concurrently;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 7 of U8, written BEFORE the feedback code from the rule (BR6.3, NFR4.3, unit-test-instructions.md "limite
 * atômico"): 20 submissions of the SAME person at the same instant, behind a start barrier, give EXACTLY 10 accepted and
 * 10 refused with 429, in each of 50 repetitions. Each repetition uses a fresh guest, so no window carries over.
 */
class FeedbackRateLimitIT extends AbstractU8IT {

    private static final int REPETITIONS = 50;
    private static final int SIMULTANEOUS = 20;

    @Test
    void twentySimultaneousSubmissionsOfOnePersonAcceptExactlyTen() throws Exception {
        for (int round = 0; round < REPETITIONS; round++) {
            Guest guest = newGuest();
            List<Callable<MockHttpServletResponse>> sends = new ArrayList<>();
            for (int i = 0; i < SIMULTANEOUS; i++) {
                String text = "Envio simultâneo " + round + "-" + i;
                sends.add(() -> postFeedback(guest.bearer(), "S2", text, UUID.randomUUID()));
            }

            @SuppressWarnings("unchecked")
            List<Concurrently.Outcome<MockHttpServletResponse>> answers =
                    Concurrently.run(sends.toArray(new Callable[0]));

            assertThat(answers).as("round %d", round).noneMatch(Concurrently.Outcome::failed);
            long accepted =
                    answers.stream().filter(a -> a.value().getStatus() == 201).count();
            long limited =
                    answers.stream().filter(a -> a.value().getStatus() == 429).count();
            assertThat(accepted).as("accepted in round %d", round).isEqualTo(10);
            assertThat(limited).as("refused in round %d", round).isEqualTo(10);
            assertThat(jdbc.sql("select count(*) from feedback_entry where user_id = :user")
                            .param("user", guest.principal().userId())
                            .query(Long.class)
                            .single())
                    .as("rows in round %d", round)
                    .isEqualTo(10);
        }
    }
}
