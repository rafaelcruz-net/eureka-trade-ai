package com.memetrade.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Bodies of the administrator entry of C8 (U8): the same shapes as C1 without the invitation, and the AdminSession.
 * No field accepts a secret phrase or a private key; any other field is {@code 400 VALIDATION_FAILED}.
 */
public final class AdminAuthDtos {

    private AdminAuthDtos() {}

    @Schema(name = "AdminChallengeRequest")
    public record AdminChallengeRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Endereço público Solana") @NotBlank
            String walletAddress) {}

    @Schema(name = "AdminVerifyRequest")
    public record AdminVerifyRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank
            String walletAddress,

            @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank
            String message,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Assinatura em base58") @NotBlank
            String signature,

            @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull
            AuthDtos.WalletProvider provider) {

        @Override
        public String toString() {
            // The message (nonce) and the signature never reach logs.
            return "AdminVerifyRequest[provider=" + provider + "]";
        }
    }

    /** The administrator session; the refresh token goes only to the HttpOnly cookie. */
    @Schema(name = "AdminSession")
    public record AdminSession(
            @Schema(requiredMode = RequiredMode.REQUIRED) String accessToken,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant accessTokenExpiresAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant sessionExpiresAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "ADMIN")
            String role,

            @Schema(description = "Carteira mascarada (4 + 4) do administrador, para o cabeçalho do app")
            String walletMasked) {

        @Override
        public String toString() {
            return "AdminSession[role=" + role + "]";
        }
    }
}
