package com.memetrade.administration.web;

import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.SystemStatus;
import com.memetrade.administration.domain.SystemStatus.Part;
import com.memetrade.marketdata.api.SourceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Bodies of {@code GET /api/v1/admin/system-status} (C8 with the U8 additions) and {@code GET /api/v1/admin/tenants}.
 * Each part is a tagged union: {@code status: OK} with {@code value}, or {@code status: UNKNOWN} with {@code reason}
 * (BR3.3: a missing count never becomes zero).
 */
public final class SystemStatusDtos {

    private SystemStatusDtos() {}

    private static final String STATUS =
            "OK com value, ou UNKNOWN com reason (nunca zero no lugar de um valor ausente)";

    @Schema(name = "CountPart", description = STATUS)
    public record CountPart(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"OK", "UNKNOWN"})
            String status,

            Long value,

            @Schema(allowableValues = {"TIMEOUT", "UNAVAILABLE"})
            String reason) {}

    @Schema(name = "CountMapPart", description = STATUS)
    public record CountMapPart(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"OK", "UNKNOWN"})
            String status,

            Map<String, Long> value,

            @Schema(allowableValues = {"TIMEOUT", "UNAVAILABLE"})
            String reason) {}

    @Schema(name = "KillSwitchSummary")
    public record KillSwitchSummary(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ON", "OFF"})
            String state,

            @Schema(requiredMode = RequiredMode.REQUIRED) int pendingBots,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean reconcileNeeded) {}

    @Schema(name = "KillSwitchPart", description = STATUS)
    public record KillSwitchPart(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"OK", "UNKNOWN"})
            String status,

            KillSwitchSummary value,

            @Schema(allowableValues = {"TIMEOUT", "UNAVAILABLE"})
            String reason) {}

    @Schema(name = "SourceState")
    public record SourceView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String sourceId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean simulated,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "OK", "DEGRADED", "FAILED"})
            String status,

            @Schema(format = "date-time", description = "Última coleta bem-sucedida; ausente antes da primeira")
            Instant lastUpdatedAt) {}

    @Schema(name = "SourcesPart", description = STATUS)
    public record SourcesPart(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"OK", "UNKNOWN"})
            String status,

            List<SourceView> value,

            @Schema(allowableValues = {"TIMEOUT", "UNAVAILABLE"})
            String reason) {}

    /** Counts and the state of the sources only (BR3.1); never an identifier of a person, bot, wallet or order. */
    @Schema(name = "SystemStatus")
    public record SystemStatusView(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant generatedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED) CountPart guestCount,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Chaves PENDING, USED, EXPIRED, REVOKED")
            CountMapPart inviteCounts,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Chaves de BotState, com CLOSING_PENDING")
            CountMapPart botCountsByState,

            @Schema(requiredMode = RequiredMode.REQUIRED) KillSwitchPart killSwitch,
            @Schema(requiredMode = RequiredMode.REQUIRED) SourcesPart sources,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Eventos de auditoria ainda na tabela de saída")
            CountPart auditOutboxPending,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Ordens recusadas de todos os tenants desde 00:00 UTC de hoje (acréscimo da U8)")
            CountPart rejectedOrdersToday) {

        static SystemStatusView of(SystemStatus status) {
            return new SystemStatusView(
                    status.generatedAt(),
                    count(status.guestCount()),
                    countMap(status.inviteCounts()),
                    countMap(status.botCountsByState()),
                    killSwitchPart(status.killSwitch()),
                    sourcesPart(status.sources()),
                    count(status.auditOutboxPending()),
                    count(status.rejectedOrdersToday()));
        }
    }

    @Schema(name = "TenantOption")
    public record TenantOptionView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID tenantId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name) {}

    static CountPart count(Part<Long> part) {
        return fold(part, value -> new CountPart("OK", value, null), reason -> new CountPart("UNKNOWN", null, reason));
    }

    static <K extends Enum<K>> CountMapPart countMap(Part<Map<K, Long>> part) {
        return fold(
                part,
                value -> {
                    Map<String, Long> named = new TreeMap<>();
                    value.forEach((key, count) -> named.put(key.name(), count));
                    return new CountMapPart("OK", named, null);
                },
                reason -> new CountMapPart("UNKNOWN", null, reason));
    }

    static KillSwitchPart killSwitchPart(Part<KillSwitchSnapshot> part) {
        return fold(
                part,
                value -> new KillSwitchPart(
                        "OK",
                        new KillSwitchSummary(value.state().name(), value.pendingBots(), value.reconcileNeeded()),
                        null),
                reason -> new KillSwitchPart("UNKNOWN", null, reason));
    }

    static SourcesPart sourcesPart(Part<List<SourceStatus>> part) {
        return fold(
                part,
                value -> new SourcesPart(
                        "OK",
                        value.stream()
                                .map(source -> new SourceView(
                                        source.source().sourceId(),
                                        source.source().name(),
                                        source.source().simulated(),
                                        source.status().name(),
                                        source.lastUpdatedAt().orElse(null)))
                                .toList(),
                        null),
                reason -> new SourcesPart("UNKNOWN", null, reason));
    }

    private static <T, R> R fold(Part<T> part, Function<T, R> known, Function<String, R> unknown) {
        return switch (part) {
            case Part.Known<T> value -> known.apply(value.value());
            case Part.Unknown<T> missing -> unknown.apply(missing.reason().name());
        };
    }
}
