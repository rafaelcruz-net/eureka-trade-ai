package com.memetrade.identity.domain.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.SyntheticIds;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Written before the implementation (custom ordering: financial core, deduplication by idempotency key) from BR1.10
 * and the common idempotency rule of contract-summary.md.
 */
@Tag("u1")
class IdempotencyServiceTest {

    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final MutableClock clock = new MutableClock();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final IdempotencyService service = new IdempotencyService(store, clock, DEFAULT_RETENTION);
    private final WalletAddress wallet = WalletAddress.of(SyntheticIds.mint());
    private final AtomicInteger effects = new AtomicInteger();

    @Test
    void sameKeyAndSameContentReturnTheSameResultWithASingleEffect() {
        IdempotencyRequest request = challenge(UUID.randomUUID(), "{\"walletAddress\":\"a\"}");

        IdempotentResponse first = service.execute(request, challengeAction("{\"nonce\":\"n1\"}"));
        IdempotentResponse second = service.execute(request, challengeAction("{\"nonce\":\"n2\"}"));

        assertThat(effects).hasValue(1);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.body()).isEqualTo("{\"nonce\":\"n1\"}");
    }

    @Test
    void sameKeyWithDifferentContentIsIdempotencyConflict() {
        UUID key = UUID.randomUUID();
        service.execute(challenge(key, "{\"walletAddress\":\"a\"}"), challengeAction("{}"));

        assertThatThrownBy(() -> service.execute(challenge(key, "{\"walletAddress\":\"b\"}"), challengeAction("{}")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
                    assertThat(e.code().status()).isEqualTo(409);
                });
        assertThat(effects).hasValue(1);
    }

    @Test
    void scopeIsWalletPlusOperationBeforeLoginAndTenantPersonOperationAfter() {
        Principal principal = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

        IdempotencyScope preLogin = IdempotencyScope.preLogin(wallet, IdempotentOperation.AUTH_CHALLENGE);
        IdempotencyScope afterLogin = IdempotencyScope.authenticated(principal, IdempotentOperation.AUTH_LOGOUT);

        assertThat(preLogin.value()).isEqualTo("wallet:" + wallet.full() + ":auth.challenge");
        assertThat(afterLogin.value())
                .isEqualTo("tenant:" + principal.tenantId() + ":user:" + principal.userId() + ":auth.logout");
    }

    @Test
    void theSameKeyInDifferentScopesIsIndependent() {
        UUID key = UUID.randomUUID();
        WalletAddress other = WalletAddress.of(SyntheticIds.mint());
        String digest = RequestDigest.ofJson("{}".getBytes(StandardCharsets.UTF_8));

        service.execute(
                new IdempotencyRequest(
                        IdempotentOperation.AUTH_CHALLENGE,
                        IdempotencyScope.preLogin(wallet, IdempotentOperation.AUTH_CHALLENGE),
                        new IdempotencyKey(key),
                        digest),
                challengeAction("{}"));
        service.execute(
                new IdempotencyRequest(
                        IdempotentOperation.AUTH_CHALLENGE,
                        IdempotencyScope.preLogin(other, IdempotentOperation.AUTH_CHALLENGE),
                        new IdempotencyKey(key),
                        digest),
                challengeAction("{}"));

        assertThat(effects).hasValue(2);
    }

    @Test
    void challengeResponseIsKeptOnlyUntilTheIssuedChallengeExpires() {
        IdempotencyRequest request = challenge(UUID.randomUUID(), "{}");
        service.execute(request, challengeAction("{\"nonce\":\"first\"}"));

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(service.execute(request, challengeAction("{\"nonce\":\"second\"}"))
                        .body())
                .isEqualTo("{\"nonce\":\"first\"}");

        clock.advance(Duration.ofSeconds(1));
        IdempotentResponse afterExpiry = service.execute(request, challengeAction("{\"nonce\":\"second\"}"));

        assertThat(afterExpiry.replayed()).isFalse();
        assertThat(afterExpiry.body()).isEqualTo("{\"nonce\":\"second\"}");
        assertThat(effects).hasValue(2);
    }

    @Test
    void otherRoutesKeepTheResponseFor24Hours() {
        IdempotencyRequest logout = logout(UUID.randomUUID());
        service.execute(logout, plainAction(204, ""));

        clock.advance(DEFAULT_RETENTION.minusSeconds(1));
        assertThat(service.execute(logout, plainAction(204, "")).replayed()).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.execute(logout, plainAction(204, "")).replayed()).isFalse();
        assertThat(effects).hasValue(2);
    }

    @Test
    void twoSimultaneousRequestsWithTheSameKeyProduceASingleEffect() throws Exception {
        IdempotencyRequest request = challenge(UUID.randomUUID(), "{}");
        CountDownLatch insideAction = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Object> first = CompletableFuture.supplyAsync(
                    () -> service.execute(request, () -> {
                        insideAction.countDown();
                        await(release);
                        return challengeAction("{\"nonce\":\"only\"}").get();
                    }),
                    pool);
            assertThat(insideAction.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Object> second = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return service.execute(request, challengeAction("{\"nonce\":\"duplicate\"}"));
                        } catch (ApiException conflict) {
                            return conflict;
                        }
                    },
                    pool);
            Object secondResult = second.get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            assertThat(effects).hasValue(1);
            assertThat(secondResult).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code())
                    .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theStoredResponseNeverContainsAnAccessOrRefreshToken() {
        IdempotencyRequest request = logout(UUID.randomUUID());

        assertThatThrownBy(() -> service.execute(request, plainAction(200, "{\"accessToken\":\"x\"}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.execute(request, plainAction(200, "{\"refreshToken\":\"y\"}")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.size()).isZero();
    }

    @Test
    void verificationNeverStoresAResponse() {
        IdempotencyRequest verify = new IdempotencyRequest(
                IdempotentOperation.AUTH_VERIFY,
                IdempotencyScope.preLogin(wallet, IdempotentOperation.AUTH_VERIFY),
                new IdempotencyKey(UUID.randomUUID()),
                RequestDigest.ofJson("{}".getBytes(StandardCharsets.UTF_8)));

        service.execute(verify, plainAction(200, "{\"role\":\"BETA_USER\"}"));
        service.execute(verify, plainAction(200, "{\"role\":\"BETA_USER\"}"));

        assertThat(effects).hasValue(2);
        assertThat(store.size()).isZero();
    }

    @Test
    void aFailedActionLeavesNothingStoredSoTheKeyCanBeRetried() {
        IdempotencyRequest request = logout(UUID.randomUUID());

        assertThatThrownBy(() -> service.execute(request, () -> {
                    throw new IllegalStateException("database down");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.size()).isZero();
        assertThat(service.execute(request, plainAction(204, "")).replayed()).isFalse();
    }

    @Test
    void missingOrNonUuidIdempotencyKeyIsRejectedWith400() {
        for (String header : new String[] {null, "", "abc", "123e4567-e89b-12d3-a456"}) {
            assertThatThrownBy(() -> IdempotencyKey.parse(header))
                    .as(String.valueOf(header))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.code().status()).isEqualTo(400);
                        assertThat(e.fields()).extracting("field").containsExactly("Idempotency-Key");
                    });
        }
        UUID valid = UUID.randomUUID();
        assertThat(IdempotencyKey.parse(valid.toString()).value()).isEqualTo(valid);
    }

    @Test
    void requestDigestIgnoresKeyOrderAndWhitespaceButNotValues() {
        String a = RequestDigest.ofJson("{\"a\":1,\"b\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        String b = RequestDigest.ofJson("{ \"b\" : \"x\", \"a\" : 1 }".getBytes(StandardCharsets.UTF_8));
        String c = RequestDigest.ofJson("{\"a\":1,\"b\":\"y\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c);
        assertThat(RequestDigest.ofJson(new byte[0])).hasSize(64);
    }

    @Test
    void expiredRecordsArePurgedInBatches() {
        for (int i = 0; i < 3; i++) {
            service.execute(logout(UUID.randomUUID()), plainAction(204, ""));
        }
        clock.advance(DEFAULT_RETENTION.plusSeconds(1));

        assertThat(service.purgeExpired(2)).isEqualTo(2);
        assertThat(service.purgeExpired(2)).isEqualTo(1);
        assertThat(store.size()).isZero();
    }

    private IdempotencyRequest challenge(UUID key, String body) {
        return new IdempotencyRequest(
                IdempotentOperation.AUTH_CHALLENGE,
                IdempotencyScope.preLogin(wallet, IdempotentOperation.AUTH_CHALLENGE),
                new IdempotencyKey(key),
                RequestDigest.ofJson(body.getBytes(StandardCharsets.UTF_8)));
    }

    private IdempotencyRequest logout(UUID key) {
        Principal principal = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);
        return new IdempotencyRequest(
                IdempotentOperation.AUTH_LOGOUT,
                IdempotencyScope.authenticated(principal, IdempotentOperation.AUTH_LOGOUT),
                new IdempotencyKey(key),
                RequestDigest.ofJson(new byte[0]));
    }

    /** A challenge whose result expires 5 minutes after issue, like the real login challenge. */
    private Supplier<IdempotentOutcome> challengeAction(String body) {
        return () -> {
            effects.incrementAndGet();
            return new IdempotentOutcome(
                    IdempotentResponse.fresh(200, body), clock.instant().plus(Duration.ofMinutes(5)));
        };
    }

    private Supplier<IdempotentOutcome> plainAction(int status, String body) {
        return () -> {
            effects.incrementAndGet();
            return IdempotentOutcome.of(IdempotentResponse.fresh(status, body));
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
