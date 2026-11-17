package com.memetrade.realexits.web;

import com.memetrade.realexits.application.ConditionalService;
import com.memetrade.realexits.domain.CancellationWatch;
import com.memetrade.realexits.domain.ConditionalOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The bodies of the routes R5 of the addendum of the U7. Prices are {@code DecimalString} in USD (BR3.2): an exact
 * decimal written as text, never a JSON number, so no floating point exists anywhere between the browser and the
 * database. Quantities are base units as text. No address of the person and no transaction travel here.
 */
public final class ConditionalDtos {

    /** A DecimalString of a price: digits, an optional point and at most 18 decimal places; no sign, no exponent. */
    static final Pattern DECIMAL_STRING = Pattern.compile("^(0|[1-9][0-9]{0,19})(\\.[0-9]{1,18})?$");

    private ConditionalDtos() {}

    @Schema(name = "CreateConditionalOrderRequest", description = "Pedido de stop, alvo ou OCO de uma posição REAL")
    public record CreateRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Mint da posição (Base58)")
            String positionMint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"STOP", "TAKE_PROFIT", "OCO"})
            String kind,

            @Schema(description = "DecimalString em USD; obrigatório em STOP e OCO", example = "0.00008")
            String stopPriceUsd,

            @Schema(description = "DecimalString em USD; obrigatório em TAKE_PROFIT e OCO", example = "0.00015")
            String takeProfitPriceUsd) {}

    @Schema(
            name = "ConditionalSignatureRequest",
            description = "A assinatura da carteira principal: do consentimento (caminho simulado) ou da transação")
    public record SignatureRequest(
            @Schema(description = "Assinatura Base58 da mensagem de consentimento (signMessage)")
            String signature,

            @Schema(description = "Transação assinada pela carteira principal (Base64)")
            String signedTransactionBase64) {}

    @Schema(name = "ConditionalSigningSession", description = "O que a carteira principal assina, e até quando")
    public record SigningSession(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"MESSAGE", "TRANSACTION"})
            String kind,

            @Schema(description = "Mensagem de consentimento montada pelo servidor (kind MESSAGE)")
            String message,

            @Schema(description = "Transação montada pelo servidor (kind TRANSACTION), em Base64")
            String transactionBase64,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant deadline,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant serverTime) {

        static SigningSession of(ConditionalService.SigningSession session) {
            return new SigningSession(
                    session.kind().name(),
                    session.message().orElse(null),
                    session.transactionBase64().orElse(null),
                    session.deadline(),
                    session.serverTime());
        }
    }

    @Schema(name = "ConditionalOrder", description = "Uma saída automática (stop, alvo ou OCO) de uma posição REAL")
    public record ConditionalOrderView(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID conditionalOrderId,

            @Schema(requiredMode = RequiredMode.REQUIRED) String positionMint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"STOP", "TAKE_PROFIT", "OCO"})
            String kind,

            @Schema(description = "DecimalString em USD") String stopPriceUsd,

            @Schema(description = "DecimalString em USD") String takeProfitPriceUsd,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Quantidade em unidades base, como texto")
            String amountBaseUnits,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {
                        "PENDING_SIGNATURE",
                        "OPEN",
                        "CANCELLING",
                        "FILLED",
                        "CANCELLED",
                        "EXPIRED",
                        "FAILED"
                    })
            String state,

            @Schema(allowableValues = {"STOP", "TAKE_PROFIT"})
            String filledSide,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Cancelamento automático parado há 15 minutos ou mais (BR6.7)")
            boolean needsUserCancellation,

            @Schema(format = "date-time") Instant filledAt,

            @Schema(description = "Assinatura do preenchimento ou, antes dele, da criação")
            String txSignature,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt) {

        static ConditionalOrderView of(ConditionalOrder conditional, Instant now) {
            return new ConditionalOrderView(
                    conditional.conditionalOrderId(),
                    conditional.positionMint(),
                    conditional.kind().name(),
                    conditional.stopPriceUsd().map(ConditionalDtos::plain).orElse(null),
                    conditional.takeProfitPriceUsd().map(ConditionalDtos::plain).orElse(null),
                    Long.toString(conditional.amountBaseUnits()),
                    conditional.state().name(),
                    conditional.filledSide().map(Enum::name).orElse(null),
                    CancellationWatch.needsUserCancellation(conditional.state(), conditional.cancellingSince(), now),
                    conditional.filledAt().orElse(null),
                    conditional.txSignature().orElse(null),
                    conditional.createdAt());
        }
    }

    @Schema(name = "CreatedConditionalOrder", description = "A condicional criada e a sessão de assinatura aberta")
    public record Created(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID conditionalOrderId,

            @Schema(requiredMode = RequiredMode.REQUIRED) ConditionalOrderView conditionalOrder,

            @Schema(requiredMode = RequiredMode.REQUIRED) SigningSession signingSession,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "BR3.8: o stop não tem execução garantida (sempre verdadeiro)")
            boolean stopNotGuaranteed) {}

    @Schema(name = "ConditionalOrderPage", description = "Condicionais da pessoa, mais recentes primeiro")
    public record Page(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ConditionalOrderView> items,
            String nextCursor,
            @Schema(requiredMode = RequiredMode.REQUIRED) long total) {}

    @Schema(
            name = "ConditionalQuote",
            description = "Posição e preço atual em USD para o diálogo \"Definir stop e alvo\" (BR8.2, BR2.6)")
    public record QuoteView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String positionMint,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Posição inteira em unidades base, como texto")
            String positionBaseUnits,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"KNOWN", "UNKNOWN"})
            String priceStatus,

            @Schema(description = "DecimalString em USD; ausente quando desconhecido")
            String priceUsd,

            @Schema(description = "Fonte do preço") String source,

            @Schema(format = "date-time") Instant observedAt,

            @Schema(description = "Menor stop aceito (50% do preço atual), DecimalString")
            String minStopPriceUsd,

            @Schema(description = "Maior alvo aceito (10 vezes o preço atual), DecimalString")
            String maxTakeProfitPriceUsd) {

        static QuoteView of(ConditionalService.Quote quote) {
            return new QuoteView(
                    quote.positionMint(),
                    Long.toString(quote.positionBaseUnits()),
                    quote.priceUsd().isPresent() ? "KNOWN" : "UNKNOWN",
                    quote.priceUsd().map(ConditionalDtos::plain).orElse(null),
                    quote.source().orElse(null),
                    quote.observedAt().orElse(null),
                    quote.minStopPriceUsd().map(ConditionalDtos::plain).orElse(null),
                    quote.maxTakeProfitPriceUsd().map(ConditionalDtos::plain).orElse(null));
        }
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
