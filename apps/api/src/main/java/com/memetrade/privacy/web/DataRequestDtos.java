package com.memetrade.privacy.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import com.memetrade.privacy.domain.request.AccessSummary;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of P1, the data subject requests of the guest (contract-summary.md P1). */
public final class DataRequestDtos {

    private DataRequestDtos() {}

    static final String TYPES = "ACCESS, CORRECTION, DELETION, OTHER";

    /**
     * Exactly these fields; any other one, {@code tenantId} or {@code userId} included, is {@code 400 VALIDATION_FAILED}
     * (closed body): tenant and person come only from the token.
     */
    @Schema(name = "CreateDataRequest")
    public record CreateDataRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACCESS", "CORRECTION", "DELETION", "OTHER"})
            String type,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    minLength = 1,
                    maxLength = 1000,
                    description = "Texto do pedido, de 1 a 1.000 caracteres (pontos de código)")
            String details) {

        @Override
        public String toString() {
            return "CreateDataRequest[type=" + type + "]";
        }
    }

    /**
     * A request as the person sees it (BR4.4): {@code response}, {@code refusalReason} and {@code accessSummary} are
     * null until the request is DONE or REFUSED.
     */
    @Schema(name = "DataRequestView")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DataRequestView(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID requestId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACCESS", "CORRECTION", "DELETION", "OTHER"})
            String type,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"RECEIVED", "AWAITING_VERIFICATION", "IN_PROGRESS", "DONE", "REFUSED"})
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant receivedAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    description = "receivedAt mais 15 dias corridos")
            Instant dueAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "Resposta; null até o pedido ser concluído ou recusado")
            String response,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "Motivo da recusa; null fora de REFUSED")
            String refusalReason,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    implementation = AccessSummaryDto.class,
                    types = {"object", "null"},
                    description = "Foto do resumo de Acesso liberada na conclusão; null nos demais casos")
            AccessSummaryDto accessSummary) {

        static DataRequestView forPerson(DataSubjectRequest request) {
            DataSubjectRequest seen = request.forPerson();
            return new DataRequestView(
                    seen.requestId(),
                    seen.type().name(),
                    seen.status().name(),
                    seen.receivedAt(),
                    seen.dueAt(),
                    seen.response().orElse(null),
                    seen.refusalReason().orElse(null),
                    seen.accessSummary().map(AccessSummaryDto::of).orElse(null));
        }

        @Override
        public String toString() {
            return "DataRequestView[requestId=" + requestId + ", status=" + status + "]";
        }
    }

    /** The Access summary (BR4.8): only data of the person, the wallet masked. */
    @Schema(name = "AccessSummary")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccessSummaryDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "4 primeiros e 4 últimos caracteres")
            String walletMasked,

            @Schema(requiredMode = RequiredMode.REQUIRED) String provider,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant accountCreatedAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    types = {"string", "null"},
                    description = "Início da sessão mais recente; null antes do primeiro login")
            Instant lastLoginAt,

            @Schema(requiredMode = RequiredMode.REQUIRED) List<AcceptedVersionDto> acceptedVersions,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            long botCount,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            long paperOrderCount,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            long feedbackCount,

            @Schema(requiredMode = RequiredMode.REQUIRED) List<RequestLineDto> requests) {

        static AccessSummaryDto of(AccessSummary summary) {
            return new AccessSummaryDto(
                    summary.walletMasked(),
                    summary.provider(),
                    summary.accountCreatedAt(),
                    summary.lastLoginAt().orElse(null),
                    summary.acceptedVersions().stream()
                            .map(AcceptedVersionDto::of)
                            .toList(),
                    summary.botCount(),
                    summary.paperOrderCount(),
                    summary.feedbackCount(),
                    summary.requests().stream().map(RequestLineDto::of).toList());
        }
    }

    @Schema(name = "AcceptedVersion")
    public record AcceptedVersionDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) String version,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant acceptedAt) {

        static AcceptedVersionDto of(AcceptedVersion accepted) {
            return new AcceptedVersionDto(accepted.version(), accepted.acceptedAt());
        }
    }

    @Schema(name = "AccessSummaryRequest")
    public record RequestLineDto(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACCESS", "CORRECTION", "DELETION", "OTHER"})
            String type,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"RECEIVED", "AWAITING_VERIFICATION", "IN_PROGRESS", "DONE", "REFUSED"})
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant receivedAt) {

        static RequestLineDto of(AccessSummary.RequestLine line) {
            return new RequestLineDto(line.type().name(), line.status().name(), line.receivedAt());
        }
    }
}
