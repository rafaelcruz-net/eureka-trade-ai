package com.memetrade.identity.web;

import com.memetrade.identity.domain.login.ChallengeService.IssuedChallenge;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Request and response bodies of contract C1 (guest authentication). No field accepts a secret or private key. */
public final class AuthDtos {

    private AuthDtos() {}

    @Schema(name = "ChallengeRequest")
    public record ChallengeRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Endereço público Solana") @NotBlank
            String walletAddress) {}

    @Schema(name = "ChallengeResponse")
    public record ChallengeResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Texto exato a assinar")
            String message,

            @Schema(requiredMode = RequiredMode.REQUIRED) String nonce,
            @Schema(requiredMode = RequiredMode.REQUIRED) String domain,
            @Schema(requiredMode = RequiredMode.REQUIRED) String chain,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant issuedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant expiresAt) {

        static ChallengeResponse from(IssuedChallenge issued) {
            return new ChallengeResponse(
                    issued.message(),
                    issued.nonce(),
                    issued.domain(),
                    issued.chain(),
                    issued.issuedAt(),
                    issued.expiresAt());
        }
    }

    /** U1 accepts exactly these fields; {@code inviteCode}/{@code acceptedTermsVersion} arrive with U3. */
    @Schema(name = "VerifyRequest")
    public record VerifyRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank
            String walletAddress,

            @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank
            String message,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Assinatura em base58") @NotBlank
            String signature,

            @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull
            WalletProvider provider) {}

    public enum WalletProvider {
        PHANTOM,
        SOLFLARE
    }

    @Schema(name = "GuestSession")
    public record GuestSession(
            @Schema(requiredMode = RequiredMode.REQUIRED) String accessToken,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant accessTokenExpiresAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant sessionExpiresAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "BETA_USER")
            String role) {}
}
