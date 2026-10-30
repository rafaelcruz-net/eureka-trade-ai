package com.memetrade.identity.domain.session;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Unit-test double of the access token port: opaque random tokens mapped to their claims. */
public final class FakeAccessTokens implements AccessTokens {

    private final Map<String, Claims> issued = new ConcurrentHashMap<>();

    @Override
    public String issue(Claims claims) {
        String token = "access-" + UUID.randomUUID();
        issued.put(token, claims);
        return token;
    }

    @Override
    public Optional<Claims> verify(String token, Instant now) {
        return Optional.ofNullable(token == null ? null : issued.get(token)).filter(c -> now.isBefore(c.expiresAt()));
    }
}
