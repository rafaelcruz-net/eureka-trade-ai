package com.memetrade.identity.web;

import com.memetrade.identity.domain.invite.InviteLookupService.InviteLookup;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** Guest bodies of contract C3 (invitation lookup). */
public final class InviteDtos {

    private InviteDtos() {}

    /**
     * Answer of {@code GET /api/v1/invites/{code}} (BR2.1): derived state, validity and the current terms and risk
     * notice as plain text. No tenant, no invitation id, no personal data.
     */
    @Schema(name = "InviteLookup")
    public record InviteLookupResponse(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "USED", "EXPIRED", "REVOKED"})
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant expiresAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Versão vigente a aceitar")
            String termsVersion,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Texto simples; nunca HTML")
            String termsText,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Texto simples; nunca HTML")
            String riskNoticeText) {

        static InviteLookupResponse of(InviteLookup lookup) {
            return new InviteLookupResponse(
                    lookup.status().name(),
                    lookup.expiresAt(),
                    lookup.terms().version(),
                    lookup.terms().termsText(),
                    lookup.terms().riskNoticeText());
        }
    }
}
