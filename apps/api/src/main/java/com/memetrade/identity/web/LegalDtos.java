package com.memetrade.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.domain.legal.DeploymentFields;
import com.memetrade.identity.domain.legal.LegalSection;
import com.memetrade.identity.domain.legal.LegalText;
import com.memetrade.identity.domain.legal.LegalVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Bodies of L1 (contract-summary.md). Absent values go as explicit {@code null} (never omitted), so a page can tell
 * "a definir" and "not published" apart (BR1.6, BR1.15): {@code privacy} and the summaries are {@code null} when the
 * version is legacy, and a deployment field is {@code null} when the environment does not set it.
 */
public final class LegalDtos {

    private LegalDtos() {}

    @Schema(name = "LegalDocuments", description = "Uma versão dos termos, da política e do aviso de risco")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LegalDocumentsResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant effectiveAt,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Versão anterior ao catálogo legal (legacy): mostrada como provisória")
            boolean provisional,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Falso quando falta um campo de implantação (BR1.6)")
            boolean publishable,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            LegalTextResponse terms,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    nullable = true,
                    description = "Nulo quando a versão é legacy e não tem política (BR1.15)")
            LegalTextResponse privacy,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            LegalTextResponse riskNotice,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            DeploymentResponse deployment,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Tabela de retenção (porta J5); vazia quando a versão legacy não declara")
            List<RetentionItemResponse> retention) {

        static LegalDocumentsResponse of(
                LegalVersion version, boolean publishable, DeploymentFields deployment, List<RetentionItem> retention) {
            return new LegalDocumentsResponse(
                    version.version(),
                    version.effectiveAt(),
                    version.legacy(),
                    publishable,
                    LegalTextResponse.of(version.terms()),
                    version.privacy() == null ? null : LegalTextResponse.of(version.privacy()),
                    LegalTextResponse.of(version.riskNotice()),
                    DeploymentResponse.of(deployment),
                    retention.stream().map(RetentionItemResponse::of).toList());
        }
    }

    @Schema(name = "LegalText")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LegalTextResponse(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    nullable = true,
                    description = "5 a 7 tópicos; nulo na versão legacy")
            List<String> summary,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<LegalSectionResponse> sections) {

        static LegalTextResponse of(LegalText text) {
            return new LegalTextResponse(
                    text.summary().isEmpty() ? null : text.summary(),
                    text.sections().stream().map(LegalSectionResponse::of).toList());
        }
    }

    @Schema(name = "LegalSection")
    public record LegalSectionResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Âncora do índice")
            String id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String title,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Texto simples, nunca HTML")
            List<String> paragraphs) {

        static LegalSectionResponse of(LegalSection section) {
            return new LegalSectionResponse(section.id(), section.title(), section.paragraphs());
        }
    }

    @Schema(name = "LegalDeployment", description = "Campos de implantação; nulo = a definir")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeploymentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String storageLocation,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String internationalTransfer,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String dpoEmail) {

        static DeploymentResponse of(DeploymentFields fields) {
            return new DeploymentResponse(fields.storageLocation(), fields.internationalTransfer(), fields.dpoEmail());
        }
    }

    @Schema(name = "RetentionItem")
    public record RetentionItemResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String dataItem,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String purpose,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String period,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String endAction) {

        static RetentionItemResponse of(RetentionItem item) {
            return new RetentionItemResponse(item.dataItem(), item.purpose(), item.period(), item.endAction());
        }
    }

    @Schema(name = "LegalVersionRef", description = "Uma versão do histórico (BR1.9)")
    public record LegalVersionRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant effectiveAt,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Versão legacy, mostrada como provisória")
            boolean provisional) {

        static LegalVersionRef of(LegalVersion version) {
            return new LegalVersionRef(version.version(), version.effectiveAt(), version.legacy());
        }
    }
}
