package com.memetrade.audit.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.audit.domain.AuditEventPage;
import com.memetrade.audit.domain.AuditEventRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON shapes of the administrator query of the trail (C8 change recorded by U2, functional-spec.md). */
public final class AuditTrailDtos {

    private AuditTrailDtos() {}

    @Schema(name = "AuditEventView", description = "Evento da trilha como gravado (BR2.4)")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AuditEventViewDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID eventId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "uuid",
                    types = {"string", "null"},
                    description = "null antes do login e em ações globais do administrador ou do sistema")
            UUID tenantId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "userId, adminId, system ou endereço mascarado antes do login")
            String actor,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PERSON", "ADMIN", "SYSTEM", "ANONYMOUS"})
            String actorType,

            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 64)
            String action,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"})
            String targetRef,

            @Schema(requiredMode = RequiredMode.REQUIRED) String correlationId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant occurredAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    types = {"string", "null"},
                    description = "Instante em que o relay gravou o evento na trilha")
            Instant recordedAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Mapa texto para texto; pode conter o endereço de carteira completo")
            Map<String, String> details) {

        static AuditEventViewDto of(AuditEventRecord record) {
            return new AuditEventViewDto(
                    record.eventId(),
                    record.tenantId().orElse(null),
                    record.actor(),
                    record.actorType().name(),
                    record.action(),
                    record.targetRef(),
                    record.correlationId(),
                    record.occurredAt(),
                    record.recordedAt().orElse(null),
                    record.details());
        }
    }

    @Schema(name = "AuditEventPage", description = "Página da trilha, mais recentes primeiro (BR2.3)")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AuditEventPageDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AuditEventViewDto> items,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "null na última página")
            String nextCursor) {

        static AuditEventPageDto of(AuditEventPage page) {
            return new AuditEventPageDto(
                    page.items().stream().map(AuditEventViewDto::of).toList(),
                    page.nextCursor().map(cursor -> cursor.encode()).orElse(null));
        }
    }
}
