package com.memetrade.administration.web;

import com.memetrade.administration.domain.KillSwitchSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.Optional;

/** Bodies of the kill switch routes of C8 with the U8 additions (contract-summary.md). */
public final class KillSwitchDtos {

    private KillSwitchDtos() {}

    /** Exactly these fields; any other one is {@code 400 VALIDATION_FAILED} (closed body, NFR6.8). */
    @Schema(name = "KillSwitchRequest")
    public record KillSwitchRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ON", "OFF"})
            String state,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    minLength = 1,
                    maxLength = 500,
                    description = "Motivo, de 1 a 500 caracteres depois de tirar os espaços (BR2.6)")
            String reason) {

        @Override
        public String toString() {
            return "KillSwitchRequest[state=" + state + "]";
        }
    }

    /**
     * The kill switch as the administrator sees it (BR2.7): {@code changedBy} is the MASKED wallet;
     * {@code activeBotsToPause} comes only in the {@code GET}, for the confirmation dialog.
     */
    @Schema(name = "KillSwitch")
    public record KillSwitchView(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ON", "OFF"})
            String state,

            @Schema(description = "Carteira mascarada (4 + 4) do administrador da última mudança")
            String changedBy,

            @Schema(format = "date-time") Instant changedAt,

            @Schema(description = "Motivo da última mudança")
            String reason,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Bots cuja pausa (ou retirada) ainda falhou")
            int pendingBots,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "A última aplicação ainda está sendo confirmada pela reconciliação")
            boolean reconcileNeeded,

            @Schema(requiredMode = RequiredMode.REQUIRED) long version,

            @Schema(description = "Bots ACTIVE que o acionamento vai pausar; só no GET (AC7.2.1)")
            Long activeBotsToPause) {

        static KillSwitchView of(KillSwitchSnapshot snapshot, Optional<String> maskedBy, Long activeBots) {
            return new KillSwitchView(
                    snapshot.state().name(),
                    maskedBy.orElse(null),
                    snapshot.changedAt().orElse(null),
                    snapshot.reason().orElse(null),
                    snapshot.pendingBots(),
                    snapshot.reconcileNeeded(),
                    snapshot.version(),
                    activeBots);
        }

        @Override
        public String toString() {
            return "KillSwitchView[state=" + state + ", version=" + version + "]";
        }
    }
}
