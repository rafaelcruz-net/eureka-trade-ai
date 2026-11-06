package com.memetrade.feedback.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of C6 (guest) and of the feedback list of C8 (administrator), with the U8 additions. */
public final class FeedbackDtos {

    private FeedbackDtos() {}

    /**
     * Exactly these fields; any other one, {@code tenantId} or {@code userId} included, is {@code 400 VALIDATION_FAILED}
     * (closed body): tenant and person come only from the token (BR6.2).
     */
    @Schema(name = "FeedbackRequest")
    public record FeedbackRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9"},
                    description = "Tela de origem do comentário")
            String screen,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    minLength = 1,
                    maxLength = 2000,
                    description = "Comentário de 1 a 2.000 caracteres (pontos de código), guardado como enviado")
            String text) {

        @Override
        public String toString() {
            return "FeedbackRequest[screen=" + screen + "]";
        }
    }

    @Schema(name = "FeedbackReceipt")
    public record FeedbackReceipt(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID feedbackId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt) {}

    /** One comment for the administrator (BR6.6): never the person nor the wallet; the text is shown as plain text. */
    @Schema(name = "FeedbackItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FeedbackItem(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID feedbackId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID tenantId,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Nome do tenant (acréscimo da U8)")
            String tenantName,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9"})
            String screen,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Texto puro, como foi enviado")
            String text,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt) {

        @Override
        public String toString() {
            return "FeedbackItem[screen=" + screen + "]";
        }
    }

    @Schema(name = "FeedbackPage")
    public record FeedbackPage(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<FeedbackItem> items,

            @Schema(description = "Cursor da próxima página; ausente na última")
            String nextCursor) {}
}
