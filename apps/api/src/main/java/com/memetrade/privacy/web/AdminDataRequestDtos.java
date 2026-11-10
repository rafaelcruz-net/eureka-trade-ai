package com.memetrade.privacy.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.memetrade.privacy.domain.request.DataRequestService;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import com.memetrade.privacy.domain.request.IdentityVerification;
import com.memetrade.privacy.web.DataRequestDtos.AccessSummaryDto;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of P2, the data subject requests of the administrator (contract-summary.md P2, with the anonymization of u5-anonymization). */
public final class AdminDataRequestDtos {

    private AdminDataRequestDtos() {}

    /** One request for the administrator (P2 {@code AdminDataRequest}): the text and the contact as plain text. */
    @Schema(name = "AdminDataRequest")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminDataRequest(
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

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACCOUNT", "EMAIL"})
            String origin,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant receivedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant dueAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Texto do pedido, como foi enviado")
            String details,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "uuid",
                    types = {"string", "null"})
            UUID userId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"},
                    description = "E-mail do titular; só em pedidos por e-mail")
            String contact,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"})
            String response,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    types = {"string", "null"})
            String refusalReason,

            @Schema(requiredMode = RequiredMode.REQUIRED) boolean identityVerified,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    implementation = VerificationView.class,
                    types = {"object", "null"},
                    description = "Verificação por assinatura; null sem verificação ou na lista")
            VerificationView verification,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    implementation = AccessSummaryDto.class,
                    types = {"object", "null"},
                    description = "Foto liberada na conclusão de um Acesso")
            AccessSummaryDto accessSummary,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    implementation = AccessSummaryDto.class,
                    types = {"object", "null"},
                    description = "Prévia do resumo de um Acesso aberto ligado a uma conta; null na lista")
            AccessSummaryDto accessSummaryPreview,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    types = {"string", "null"})
            Instant replySentAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Aberto e com o prazo vencido")
            boolean overdue,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Aberto, ainda no prazo e a 3 dias ou menos dele (acréscimo da U4, BR4.16)")
            boolean dueSoon,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "A conta ligada foi anonimizada (acréscimo da U5); a tela mostra 'Conta anonimizada'")
            boolean accountAnonymized) {

        static AdminDataRequest of(
                DataRequestService.Detail detail, Instant now, Duration dueSoonWindow, boolean accountAnonymized) {
            DataSubjectRequest request = detail.request();
            return new AdminDataRequest(
                    request.requestId(),
                    request.type().name(),
                    request.status().name(),
                    request.origin().name(),
                    request.receivedAt(),
                    request.dueAt(),
                    request.details(),
                    request.userId().orElse(null),
                    request.contact().orElse(null),
                    request.response().orElse(null),
                    request.refusalReason().orElse(null),
                    request.identityVerified(),
                    detail.verification().map(VerificationView::of).orElse(null),
                    request.accessSummary().map(AccessSummaryDto::of).orElse(null),
                    detail.preview().map(AccessSummaryDto::of).orElse(null),
                    request.replySentAt().orElse(null),
                    request.isOverdue(now),
                    isDueSoon(request, now, dueSoonWindow),
                    accountAnonymized);
        }

        static AdminDataRequest listItem(
                DataSubjectRequest request, Instant now, Duration dueSoonWindow, boolean accountAnonymized) {
            return of(
                    new DataRequestService.Detail(request, java.util.Optional.empty(), java.util.Optional.empty()),
                    now,
                    dueSoonWindow,
                    accountAnonymized);
        }

        private static boolean isDueSoon(DataSubjectRequest request, Instant now, Duration window) {
            return request.status().isOpen()
                    && request.dueAt().isAfter(now)
                    && !request.dueAt().isAfter(now.plus(window));
        }

        @Override
        public String toString() {
            return "AdminDataRequest[requestId=" + requestId + ", status=" + status + "]";
        }
    }

    @Schema(name = "AdminDataRequestPage")
    public record AdminDataRequestPage(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AdminDataRequest> items,

            @Schema(description = "Cursor da próxima página; ausente na última")
            String nextCursor) {}

    /** The verification of an EMAIL request (P2 {@code VerificationView}); the message is not a secret. */
    @Schema(name = "VerificationView")
    public record VerificationView(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Mensagem a assinar: domínio, pedido, finalidade e validade; só para este pedido")
            String message,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant expiresAt,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "CONFIRMED", "EXPIRED"})
            String state) {

        static VerificationView of(IdentityVerification verification) {
            return new VerificationView(
                    verification.message(),
                    verification.expiresAt(),
                    verification.state().name());
        }
    }

    /** P2 {@code adminRegisterEmailRequest}: every value as received; the tenant comes from the account. */
    @Schema(name = "RegisterEmailDataRequest")
    public record RegisterEmailDataRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"ACCESS", "CORRECTION", "DELETION", "OTHER"})
            String type,

            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 1000)
            String details,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "email", maxLength = 254)
            String contact,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    description = "Data em que o e-mail chegou ao encarregado; nunca no futuro")
            String receivedAt,

            @Schema(
                    format = "uuid",
                    types = {"string", "null"},
                    description = "Conta ligada, opcional")
            String userId) {

        @Override
        public String toString() {
            return "RegisterEmailDataRequest[type=" + type + "]";
        }
    }

    /** P2 {@code adminTransitionDataRequest}. */
    @Schema(name = "DataRequestTransition")
    public record DataRequestTransition(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"IN_PROGRESS", "DONE", "REFUSED"})
            String to,

            @Schema(
                    types = {"string", "null"},
                    maxLength = 4000)
            String response,

            @Schema(
                    types = {"string", "null"},
                    maxLength = 500,
                    description = "Obrigatório para REFUSED")
            String refusalReason,

            @Schema(defaultValue = "false", description = "Só para Acesso concluído; exige identidade em pedido EMAIL")
            Boolean releaseAccessSummary) {

        @Override
        public String toString() {
            return "DataRequestTransition[to=" + to + "]";
        }
    }

    /** P2 {@code adminConfirmVerification}: the signature is only handed to J1; never stored or logged. */
    @Schema(name = "ConfirmVerification")
    public record ConfirmVerification(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Assinatura Base58 recebida do titular")
            String signature) {

        @Override
        public String toString() {
            return "ConfirmVerification[]";
        }
    }

    /** P2 {@code adminAnonymizeAccount} (u5-anonymization, BR5.1, BR5.9): the double confirmation, as typed. */
    @Schema(name = "AnonymizeAccountRequest")
    public record AnonymizeAccountRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Exatamente ANONIMIZAR; qualquer outro texto é 422 CONFIRMATION_REQUIRED")
            String confirmation) {}

    /**
     * entities.md AnonymizationResult (u5-anonymization; NFR1.2): internal identifiers, the instant and counts only. A
     * repetition (BR5.5) answers the same request, person and instant with {@code alreadyAnonymized} true.
     */
    @Schema(name = "AnonymizationResult")
    public record AnonymizationView(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID requestId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID userId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant anonymizedAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            int sessionsRevoked,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            int botsStopped,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            int feedbackDeleted,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "A conta já estava anonimizada; nada mudou")
            boolean alreadyAnonymized) {

        static AnonymizationView of(com.memetrade.privacy.domain.anonymization.AnonymizationResult result) {
            return new AnonymizationView(
                    result.requestId(),
                    result.userId(),
                    result.anonymizedAt(),
                    result.sessionsRevoked(),
                    result.botsStopped(),
                    result.feedbackDeleted(),
                    result.alreadyAnonymized());
        }
    }
}
