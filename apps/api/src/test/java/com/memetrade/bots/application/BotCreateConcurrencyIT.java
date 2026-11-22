package com.memetrade.bots.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.Concurrently;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1, Step 5, WRITTEN BEFORE THE CODE (NFR1.12, NFR4.9, BR1.4; team.md "deduplicação por chave
 * de idempotência"): ten simultaneous creations with the same key create exactly one bot, PAPER and REAL. Each answer
 * is the stored 201 (same body) or {@code 409 IDEMPOTENCY_CONFLICT} while the first is in progress; at least one is 201.
 */
class BotCreateConcurrencyIT extends AbstractPaperRealIT {

    private static final int REQUESTS = 10;

    @Test
    void tenSimultaneousPaperCreationsWithTheSameKeyCreateOneBot() throws Exception {
        Person person = signIn(false, false);

        assertExactlyOne(person, "PAPER");
    }

    @Test
    void tenSimultaneousRealCreationsWithTheSameKeyCreateOneBot() throws Exception {
        Person person = eligiblePerson();

        assertExactlyOne(person, "REAL");
    }

    private void assertExactlyOne(Person person, String market) throws Exception {
        UUID key = UUID.randomUUID();
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            tasks.add(() -> create(person, market, key));
        }
        @SuppressWarnings("unchecked")
        List<Concurrently.Outcome<MockHttpServletResponse>> outcomes = Concurrently.run(tasks.toArray(new Callable[0]));

        List<String> created = new ArrayList<>();
        int conflicts = 0;
        for (Concurrently.Outcome<MockHttpServletResponse> outcome : outcomes) {
            assertThat(outcome.failed())
                    .as("no request fails with an exception")
                    .isFalse();
            MockHttpServletResponse response = outcome.value();
            JsonNode body = body(response);
            if (response.getStatus() == 201) {
                created.add(body.get("botId").asText());
            } else {
                assertThat(response.getStatus())
                        .as(response.getContentAsString())
                        .isEqualTo(409);
                assertThat(body.get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
                assertThat(body.get("reason").asText()).isEqualTo("IN_PROGRESS");
                conflicts++;
            }
        }
        assertThat(created).isNotEmpty();
        assertThat(Set.copyOf(created)).hasSize(1);
        assertThat(created.size() + conflicts).isEqualTo(REQUESTS);
        assertThat(bots(person.userId())).isEqualTo(1);
        assertThat(jdbc.sql("select market from bot where owner_user_id = :u")
                        .param("u", person.userId())
                        .query(String.class)
                        .single())
                .isEqualTo(market);
    }
}
