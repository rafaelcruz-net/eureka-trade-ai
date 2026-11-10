package com.memetrade.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.identity.domain.account.AccountQueryService.AcceptedTerms;
import com.memetrade.identity.domain.account.AccountQueryService.AccountView;
import com.memetrade.identity.domain.terms.TermsStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** Guest body of the Account screen (C3 {@code GET /api/v1/account}, US1.5). */
public final class AccountDtos {

    private AccountDtos() {}

    @Schema(name = "Account")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "4 primeiros e 4 últimos caracteres")
            String walletAddressMasked,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PHANTOM", "SOLFLARE"})
            String provider,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    description = "Fim absoluto da sessão atual (UTC); pode terminar antes, depois de 8 horas sem uso")
            Instant sessionExpiresAt,

            @Schema(description = "Ausente só para conta criada sem convite (conta de smoke do perfil de teste)")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            AcceptedTermsDto acceptedTerms,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNAVAILABLE")
            String realMode,

            @Schema(
                    description = "u3-reacceptance (L2, BR3.10): situação diante da versão vigente; ausente só para"
                            + " conta sem nenhum aceite (conta semeada por teste)")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            TermsStatusDto terms) {

        static AccountResponse of(AccountView view) {
            return new AccountResponse(
                    view.walletAddressMasked(),
                    view.provider(),
                    view.sessionExpiresAt(),
                    view.acceptedTerms().map(AcceptedTermsDto::of).orElse(null),
                    view.realMode(),
                    view.terms().map(TermsStatusDto::of).orElse(null));
        }
    }

    /** u3-reacceptance (L2): the TermsStatus of the Account and of {@code POST /api/v1/terms/acceptances}. */
    @Schema(name = "TermsStatus")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TermsStatusDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Versão do aceite mais recente")
            String acceptedVersion,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Versão vigente efetiva")
            String currentVersion,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time", description = "UTC")
            Instant acceptedAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Verdadeiro quando há um aceite da versão vigente (a trava deixa passar)")
            boolean upToDate) {

        public static TermsStatusDto of(TermsStatus status) {
            return new TermsStatusDto(
                    status.acceptedVersion(), status.currentVersion(), status.acceptedAt(), status.upToDate());
        }
    }

    @Schema(name = "AcceptedTerms")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AcceptedTermsDto(
            @Schema(requiredMode = RequiredMode.REQUIRED) String version,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time", description = "UTC")
            Instant acceptedAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "Texto simples dos termos daquela versão")
            String termsText,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "Texto simples do aviso de risco daquela versão")
            String riskNoticeText) {

        static AcceptedTermsDto of(AcceptedTerms accepted) {
            return new AcceptedTermsDto(
                    accepted.version(),
                    accepted.acceptedAt(),
                    accepted.document() == null ? null : accepted.document().termsText(),
                    accepted.document() == null ? null : accepted.document().riskNoticeText());
        }
    }
}
