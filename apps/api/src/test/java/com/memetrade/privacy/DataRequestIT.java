package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Role;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 6 of u4-data-requests (P1; BR4.1 to BR4.4, BR4.20; NFR2.1, NFR2.4, NFR3.8; US5.1, US5.2): the flow of the guest
 * on the real api and database - creation, list, detail, isolation between people and tenants, idempotency, the limit
 * per hour by the clock, the unique index under a race and the route reachable during the reacceptance lock.
 */
class DataRequestIT extends AbstractDataRequestIT {

    @Test
    void aGuestCreatesListsAndOpensARequestDueFifteenDaysLater() throws Exception {
        WalletGuest guest = newWalletGuest();
        Instant now = clock.instant();

        JsonNode created = body(calls.create(guest.bearer(), "ACCESS", "Quero meus dados."), 201);

        assertThat(created.get("type").asText()).isEqualTo("ACCESS");
        assertThat(created.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(Instant.parse(created.get("receivedAt").asText())).isEqualTo(now);
        assertThat(Instant.parse(created.get("dueAt").asText())).isEqualTo(now.plus(Duration.ofDays(15)));
        assertThat(created.has("response")).isTrue();
        assertThat(created.get("response").isNull()).isTrue();
        assertThat(created.get("accessSummary").isNull()).isTrue();
        String id = created.get("requestId").asText();
        JsonNode list = body(calls.list(guest.bearer()), 200);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("requestId").asText()).isEqualTo(id);
        assertThat(body(calls.get(guest.bearer(), id), 200).get("status").asText())
                .isEqualTo("RECEIVED");
        assertThat(events(AuditActions.DATA_REQUEST_CREATED, id)).isEqualTo(1);
    }

    @Test
    void anotherPersonsRequestIsNotFoundOnEveryRoute() throws Exception {
        WalletGuest owner = newWalletGuest();
        WalletGuest stranger = newWalletGuest();
        String id = created(owner.bearer(), "OTHER");

        MockHttpServletResponse read = calls.get(stranger.bearer(), id);
        JsonNode strangerList = body(calls.list(stranger.bearer()), 200);
        MockHttpServletResponse withTenant = calls.createBody(
                stranger.bearer(),
                "{\"type\":\"OTHER\",\"details\":\"x\",\"tenantId\":\""
                        + owner.principal().tenantId() + "\"}",
                UUID.randomUUID());

        assertThat(read.getStatus()).isEqualTo(404);
        assertThat(read.getContentAsString()).doesNotContain(id).doesNotContain("Texto sintético");
        assertThat(strangerList).isEmpty();
        assertThat(withTenant.getStatus()).isEqualTo(400);
        assertThat(calls.get(stranger.bearer(), "not-a-uuid").getStatus()).isEqualTo(404);
        assertThat(calls.get(owner.bearer(), UUID.randomUUID().toString()).getStatus())
                .isEqualTo(404);
        assertThat(jdbc.sql("select tenant_id from data_subject_request where request_id = :id")
                        .param("id", UUID.fromString(id))
                        .query(UUID.class)
                        .single())
                .isEqualTo(owner.principal().tenantId());
    }

    @Test
    void theSameKeyAndBodyReplayTheRequestAndAnotherBodyIs409() throws Exception {
        WalletGuest guest = newWalletGuest();
        UUID key = UUID.randomUUID();

        JsonNode first = body(calls.create(guest.bearer(), "CORRECTION", "Corrijam meu nome.", key), 201);
        JsonNode again = body(calls.create(guest.bearer(), "CORRECTION", "Corrijam meu nome.", key), 201);
        MockHttpServletResponse other = calls.create(guest.bearer(), "CORRECTION", "Outro texto.", key);

        assertThat(again.get("requestId").asText())
                .isEqualTo(first.get("requestId").asText());
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(calls.body(other).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(events(
                        AuditActions.DATA_REQUEST_CREATED,
                        first.get("requestId").asText()))
                .isEqualTo(1);
        assertThat(body(calls.list(guest.bearer()), 200)).hasSize(1);
    }

    @Test
    void anInvalidBodyIs400NamingTheFieldsAndStoresNothing() throws Exception {
        WalletGuest guest = newWalletGuest();
        String longText = "segredo-" + "x".repeat(1000);

        MockHttpServletResponse refused = calls.create(guest.bearer(), "EVERYTHING", longText);

        assertThat(refused.getStatus()).isEqualTo(400);
        JsonNode problem = calls.body(refused);
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.toString()).contains("type").contains("details").doesNotContain("segredo-");
        assertThat(calls.create(guest.bearer(), "OTHER", "  ").getStatus()).isEqualTo(400);
        assertThat(body(calls.list(guest.bearer()), 200)).isEmpty();
    }

    @Test
    void aSecondOpenRequestOfTheSameTypeIs409() throws Exception {
        WalletGuest guest = newWalletGuest();
        created(guest.bearer(), "DELETION");

        MockHttpServletResponse second = calls.create(guest.bearer(), "DELETION", "De novo.");

        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(calls.body(second).get("code").asText()).isEqualTo("DATA_REQUEST_ALREADY_OPEN");
        assertThat(calls.create(guest.bearer(), "ACCESS", "Outro tipo.").getStatus())
                .isEqualTo(201);
    }

    @Test
    void theEleventhRequestOfTheHourIs429WithRetryAfterAndTheKeyIsNotConsumed() throws Exception {
        WalletGuest guest = newWalletGuest();
        Instant start = clock.instant();
        for (int i = 0; i < 10; i++) {
            String id = created(guest.bearer(), "OTHER");
            closeDirectly(id, clock.instant());
            clock.advance(Duration.ofMinutes(1));
        }
        UUID key = UUID.randomUUID();

        MockHttpServletResponse limited = calls.create(guest.bearer(), "OTHER", "O décimo primeiro.", key);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(calls.body(limited).get("code").asText()).isEqualTo("RATE_LIMITED");
        long retryAfter = Long.parseLong(limited.getHeader("Retry-After"));
        assertThat(retryAfter)
                .isEqualTo(Duration.between(clock.instant(), start.plus(Duration.ofHours(1)))
                        .toSeconds());
        clock.set(start.plus(Duration.ofHours(1)).plusSeconds(1));
        // The access token lives 15 minutes of the fixed clock: sign in again after moving it.
        assertThat(calls.create(relogin(guest).bearer(), "OTHER", "O décimo primeiro.", key)
                        .getStatus())
                .isEqualTo(201);
    }

    @Test
    void twoConcurrentRequestsOfTheSameTypeGiveOneRequestAndOne409() throws Exception {
        WalletGuest guest = newWalletGuest();

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(
                () -> calls.create(guest.bearer(), "ACCESS", "Corrida A.").getStatus(),
                () -> calls.create(guest.bearer(), "ACCESS", "Corrida B.").getStatus());

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failed()).isFalse());
        assertThat(outcomes).extracting(Concurrently.Outcome::value).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("""
                                select count(*) from data_subject_request
                                where user_id = :user and type = 'ACCESS' and status = 'RECEIVED'
                                """)
                        .param("user", guest.principal().userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aPersonWithoutTheCurrentTermsStillReachesTheRequests() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUserWithoutAcceptance(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String bearer = "Bearer " + auth.accessToken(auth.login(wallet));

        MockHttpServletResponse created = calls.create(bearer, "ACCESS", "Mesmo sem aceitar os termos novos.");

        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(calls.list(bearer).getStatus()).isEqualTo(200);
        assertThat(seeded.userId()).isNotNull();
        assertThat(Role.BETA_USER).isNotNull();
    }

    @Test
    void theAuditEventCarriesTheIdTypeOriginAndTargetButNeverTheText() throws Exception {
        WalletGuest guest = newWalletGuest();
        String text = "texto-unico-" + UUID.randomUUID();
        String id = body(calls.create(guest.bearer(), "OTHER", text), 201)
                .get("requestId")
                .asText();

        String payload = jdbc.sql("""
                        select payload::text from audit_outbox
                        where payload->>'action' = 'DATA_REQUEST_CREATED' and payload->'details'->>'requestId' = :id
                        """).param("id", id).query(String.class).single();

        JsonNode event = json.readTree(payload);
        assertThat(payload).doesNotContain(text);
        assertThat(event.get("actorType").asText()).isEqualTo("PERSON");
        assertThat(event.get("targetRef").asText())
                .isEqualTo("user:" + guest.principal().userId());
        assertThat(event.get("details").get("type").asText()).isEqualTo("OTHER");
        assertThat(event.get("details").get("origin").asText()).isEqualTo("ACCOUNT");
        assertThat(event.get("details").get("to").asText()).isEqualTo("RECEIVED");
    }

    @Test
    void theRequestsOfAPersonAreReadThroughTheIndexOfTenantAndPerson() {
        String plan = explain("""
                select * from data_subject_request
                where tenant_id = '00000000-0000-0000-0000-000000000001'
                  and user_id = '00000000-0000-0000-0000-000000000002'
                order by received_at desc
                """);
        String open = explain("""
                select 1 from data_subject_request
                where tenant_id = '00000000-0000-0000-0000-000000000001'
                  and user_id = '00000000-0000-0000-0000-000000000002'
                  and type = 'ACCESS' and status in ('RECEIVED', 'AWAITING_VERIFICATION', 'IN_PROGRESS')
                """);

        assertThat(plan).contains("data_subject_request_person_idx").doesNotContain("Seq Scan");
        assertThat(open).doesNotContain("Seq Scan");
    }
}
