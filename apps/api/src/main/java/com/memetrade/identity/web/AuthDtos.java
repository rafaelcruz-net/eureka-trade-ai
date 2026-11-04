package com.memetrade.identity.web;

import com.memetrade.identity.domain.login.ChallengeService.IssuedChallenge;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /**
     * Exactly these fields; any other one, {@code tenantId} included, is {@code 400 VALIDATION_FAILED} (NFR6.8), so the
     * tenant never comes from the body. {@code inviteCode} has no format check here on purpose: a malformed code is
     * refused after the signature exactly like an unknown one (NFR6.7). No field accepts a secret phrase or a key.
     */
    @Schema(name = "VerifyRequest")
    public record VerifyRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank
            String walletAddress,

            @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank
            String message,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Assinatura em base58") @NotBlank
            String signature,

            @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull
            WalletProvider provider,

            @Schema(description = "Código do convite; presente só no primeiro acesso por convite (U3)", maxLength = 128)
            @Size(max = 128)
            String inviteCode,

            @Schema(description = "Versão vigente dos termos aceita; obrigatória com inviteCode", maxLength = 32)
            @Size(max = 32)
            String acceptedTermsVersion) {

        @Override
        public String toString() {
            // The message (nonce), the signature and the invitation code never reach logs (BR4.3, BR2.3).
            return "VerifyRequest[provider=" + provider + "]";
        }
    }

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
