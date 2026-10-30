package com.memetrade.identity.domain.login;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.shared.wallet.WalletAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues login challenges (BR1.1, BR1.13) with a 128-bit single-use nonce and a 5-minute expiry from the injectable
 * clock. Requests are idempotent per wallet + operation; the stored response lives only until the challenge expires
 * (BR1.10).
 */
public class ChallengeService {

    private final LoginChallengeRepository repository;
    private final NonceGenerator nonces;
    private final IdempotencyGuard idempotency;
    private final LoginSettings settings;
    private final Clock clock;
    private final ObjectMapper json;

    public ChallengeService(
            LoginChallengeRepository repository,
            NonceGenerator nonces,
            IdempotencyGuard idempotency,
            LoginSettings settings,
            Clock clock,
            ObjectMapper json) {
        this.repository = repository;
        this.nonces = nonces;
        this.idempotency = idempotency;
        this.settings = settings;
        this.clock = clock;
        this.json = json;
    }

    /** Issues (or replays) a challenge for the wallet. */
    @Transactional
    public IssuedChallenge issue(WalletAddress wallet, IdempotencyKey key, String requestDigest) {
        IdempotencyRequest request = new IdempotencyRequest(
                IdempotentOperation.AUTH_CHALLENGE,
                IdempotencyScope.preLogin(wallet, IdempotentOperation.AUTH_CHALLENGE),
                key,
                requestDigest);
        IdempotentResponse response = idempotency.execute(request, () -> {
            IssuedChallenge issued = issueNew(wallet);
            return new IdempotentOutcome(IdempotentResponse.fresh(200, write(issued)), issued.expiresAt());
        });
        return read(response.body());
    }

    IssuedChallenge issueNew(WalletAddress wallet) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(settings.challengeTtl());
        String nonce = nonces.next();
        LoginMessage message = new LoginMessage(
                settings.domain(), wallet.full(), settings.uri(), settings.chain(), nonce, issuedAt, expiresAt);
        String text = message.render();
        repository.insert(new LoginChallenge(
                nonce,
                wallet.full(),
                settings.domain(),
                settings.chain(),
                text,
                issuedAt,
                expiresAt,
                ChallengeState.ISSUED,
                null));
        return new IssuedChallenge(text, nonce, settings.domain(), settings.chain(), issuedAt, expiresAt);
    }

    private String write(IssuedChallenge issued) {
        try {
            return json.writeValueAsString(issued);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Challenge response could not be serialised", impossible);
        }
    }

    private IssuedChallenge read(String body) {
        try {
            return json.readValue(body, IssuedChallenge.class);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Stored challenge response is not readable", corrupt);
        }
    }

    /** The challenge returned to the app; the message is the exact text to sign. */
    public record IssuedChallenge(
            String message, String nonce, String domain, String chain, Instant issuedAt, Instant expiresAt) {}
}
