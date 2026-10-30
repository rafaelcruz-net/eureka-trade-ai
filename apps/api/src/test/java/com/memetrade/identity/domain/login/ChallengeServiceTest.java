package com.memetrade.identity.domain.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.domain.idempotency.IdempotencyService;
import com.memetrade.identity.domain.idempotency.InMemoryIdempotencyStore;
import com.memetrade.identity.domain.login.ChallengeService.IssuedChallenge;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.SyntheticIds;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class ChallengeServiceTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryLoginChallengeRepository repository = new InMemoryLoginChallengeRepository();
    private final ObjectMapper json =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ChallengeService service = new ChallengeService(
            repository,
            new NonceGenerator(new SecureRandom()),
            new IdempotencyService(new InMemoryIdempotencyStore(), clock, Duration.ofHours(24)),
            new LoginSettings(URI.create("http://localhost:3000"), "solana:mainnet", Duration.ofMinutes(5), Set.of()),
            clock,
            json);
    private final WalletAddress wallet = WalletAddress.of(SyntheticIds.mint());

    @Test
    void issuesAnIssuedChallengeThatExpiresFiveMinutesLater() {
        IssuedChallenge issued = service.issue(wallet, new IdempotencyKey(UUID.randomUUID()), "d1");

        assertThat(issued.issuedAt()).isEqualTo(clock.instant());
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        LoginChallenge stored = repository.findByNonce(issued.nonce()).orElseThrow();
        assertThat(stored.state()).isEqualTo(ChallengeState.ISSUED);
        assertThat(stored.message()).isEqualTo(issued.message());
        assertThat(stored.walletAddress()).isEqualTo(wallet.full());
    }

    @Test
    void theMessageCarriesDomainAddressChainNonceAndTheNoTradingStatement() {
        IssuedChallenge issued = service.issue(wallet, new IdempotencyKey(UUID.randomUUID()), "d1");

        LoginMessage parsed = LoginMessage.parse(issued.message()).orElseThrow();
        assertThat(parsed.domain()).isEqualTo("localhost:3000").isEqualTo(issued.domain());
        assertThat(parsed.walletAddress()).isEqualTo(wallet.full());
        assertThat(parsed.chain()).isEqualTo("solana:mainnet").isEqualTo(issued.chain());
        assertThat(parsed.nonce()).isEqualTo(issued.nonce());
        assertThat(issued.message()).contains("não autoriza operações");
    }

    @Test
    void aRepeatedRequestWithTheSameKeyReturnsTheSameChallenge() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());

        IssuedChallenge first = service.issue(wallet, key, "d1");
        clock.advance(Duration.ofMinutes(1));
        IssuedChallenge second = service.issue(wallet, key, "d1");

        assertThat(second).isEqualTo(first);
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    void afterTheChallengeExpiresTheSameKeyIssuesANewOne() {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        IssuedChallenge first = service.issue(wallet, key, "d1");

        clock.advance(Duration.ofMinutes(5));
        IssuedChallenge second = service.issue(wallet, key, "d1");

        assertThat(second.nonce()).isNotEqualTo(first.nonce());
        assertThat(repository.size()).isEqualTo(2);
    }

    @Test
    void differentKeysIssueDistinctNonces() {
        IssuedChallenge a = service.issue(wallet, new IdempotencyKey(UUID.randomUUID()), "d1");
        IssuedChallenge b = service.issue(wallet, new IdempotencyKey(UUID.randomUUID()), "d1");

        assertThat(a.nonce()).isNotEqualTo(b.nonce());
    }
}
