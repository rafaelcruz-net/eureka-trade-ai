package com.memetrade.identity.infra;

import com.memetrade.identity.domain.session.AccessTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HS256 JWT access token (15 minutes) with the session id in {@code sid}. The key comes from
 * {@code ACCESS_TOKEN_SIGNING_KEY} (base64, at least 32 bytes); the token is never logged.
 */
public class HmacAccessTokens implements AccessTokens {

    static final String ISSUER = "memetrade-api";
    static final String AUDIENCE = "memetrade-guest";
    private static final int MIN_KEY_BYTES = 32;

    private final MACSigner signer;
    private final JWSVerifier verifier;

    public HmacAccessTokens(String base64Key) {
        byte[] key = decode(base64Key);
        try {
            this.signer = new MACSigner(key);
            this.verifier = new MACVerifier(key);
        } catch (JOSEException invalidKey) {
            throw new IllegalStateException("ACCESS_TOKEN_SIGNING_KEY is not a valid HMAC key", invalidKey);
        }
    }

    @Override
    public String issue(Claims claims) {
        JWTClaimsSet set = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(claims.userId().toString())
                .claim("sid", claims.sessionId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(claims.issuedAt()))
                .expirationTime(Date.from(claims.expiresAt()))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), set);
        try {
            jwt.sign(signer);
        } catch (JOSEException failure) {
            throw new IllegalStateException("Access token could not be signed", failure);
        }
        return jwt.serialize();
    }

    @Override
    public Optional<Claims> verify(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return Optional.empty();
            }
            return claimsOf(jwt.getJWTClaimsSet(), now);
        } catch (ParseException | JOSEException | IllegalArgumentException notOurToken) {
            // Anything that is not a valid token of this API (for example a login signature) is unauthenticated.
            return Optional.empty();
        }
    }

    private static Optional<Claims> claimsOf(JWTClaimsSet set, Instant now) throws ParseException {
        Date expires = set.getExpirationTime();
        Date issued = set.getIssueTime();
        String sid = set.getStringClaim("sid");
        if (expires == null
                || issued == null
                || sid == null
                || !ISSUER.equals(set.getIssuer())
                || !List.of(AUDIENCE).equals(set.getAudience())
                || !now.isBefore(expires.toInstant())) {
            return Optional.empty();
        }
        return Optional.of(new Claims(
                UUID.fromString(sid), UUID.fromString(set.getSubject()), issued.toInstant(), expires.toInstant()));
    }

    private static byte[] decode(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("ACCESS_TOKEN_SIGNING_KEY is not set");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("ACCESS_TOKEN_SIGNING_KEY must be base64");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("ACCESS_TOKEN_SIGNING_KEY must have at least 32 bytes");
        }
        return key;
    }
}
