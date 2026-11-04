package com.memetrade.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.identity.api.InvitePage;
import com.memetrade.identity.api.InviteView;
import com.memetrade.identity.api.IssuedInvite;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Administrative bodies of the invitation routes (C8, BR1.1 to BR1.4). The code appears only in the 201 answer. */
public final class AdminInviteDtos {

    private AdminInviteDtos() {}

    @Schema(name = "IssueInviteRequest")
    public record IssueInviteRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "uuid",
                    description = "Exceção à regra de tenant: o administrador escolhe um tenant ativo (ADR-002)")
            @NotNull
            UUID tenantId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    description = "Entre agora + 1 hora e agora + 30 dias, inclusive (UTC)")
            @NotNull
            Instant expiresAt) {}

    @Schema(name = "Invite")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InviteResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID inviteId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID tenantId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "USED", "EXPIRED", "REVOKED"})
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant expiresAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    types = {"string", "null"})
            Instant usedAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    types = {"string", "null"})
            Instant revokedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 4, maxLength = 4, description = "4 últimos")
            String codeSuffix,

            @Schema(
                    description = "Código completo: só na resposta 201 da emissão, nunca numa repetição",
                    minLength = 43)
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String code) {

        /** Without the code: the only form stored by the idempotency mechanism and used by list and revocation. */
        static InviteResponse of(InviteView invite) {
            return new InviteResponse(
                    invite.inviteId(),
                    invite.tenantId(),
                    invite.status().name(),
                    invite.expiresAt(),
                    invite.createdAt(),
                    invite.usedAt(),
                    invite.revokedAt(),
                    invite.codeSuffix(),
                    null);
        }

        /** The first, non-replayed answer of the issuance (BR1.3): the single time the code leaves the api. */
        static InviteResponse withCode(IssuedInvite issued) {
            InviteResponse plain = of(issued.invite());
            return new InviteResponse(
                    plain.inviteId(),
                    plain.tenantId(),
                    plain.status(),
                    plain.expiresAt(),
                    plain.createdAt(),
                    plain.usedAt(),
                    plain.revokedAt(),
                    plain.codeSuffix(),
                    issued.code());
        }

        @Override
        public String toString() {
            return "InviteResponse[inviteId=" + inviteId + ", status=" + status + "]";
        }
    }

    @Schema(name = "InvitePage")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InvitePageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<InviteResponse> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "null quando não há próxima página")
            String nextCursor) {

        static InvitePageResponse of(InvitePage page) {
            return new InvitePageResponse(
                    page.items().stream().map(InviteResponse::of).toList(), page.nextCursor());
        }
    }
}
