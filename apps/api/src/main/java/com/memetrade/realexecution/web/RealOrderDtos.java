package com.memetrade.realexecution.web;

import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.api.RealSide;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bodies of the routes R3 (contract-summary.md, U4). Lamports and base units travel as integer text, never a JSON number
 * (contract "Lamports"); instants in UTC; the on-chain signature only in {@code txSignature}; never an address.
 */
public final class RealOrderDtos {

    static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";

    private RealOrderDtos() {}

    @Schema(name = "RealTokenAmount", description = "Quantidade em unidades base, com as casas decimais do mint")
    public record TokenAmount(
            @Schema(requiredMode = RequiredMode.REQUIRED) String mint,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,30}$")
            String baseUnits,

            @Schema(description = "casas decimais do mint; ausente quando desconhecidas")
            Integer decimals) {}

    @Schema(name = "RealOrder", description = "Ordem REAL (contrato R3)")
    public record RealOrder(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID realOrderId,

            @Schema(format = "uuid") UUID botId,
            @Schema(format = "uuid") UUID liquidationId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BOT", "USER_LIQUIDATION"})
            String origin,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BUY", "SELL"})
            String side,

            @Schema(requiredMode = RequiredMode.REQUIRED) TokenAmount input,

            @Schema(description = "saída lida da rede na confirmação, ou a esperada antes dela")
            TokenAmount output,

            TokenAmount minOutput,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"SIGNED", "AUTO_EXITS", "AUTO_FULL"})
            String operationMode,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {
                        "PROPOSED",
                        "QUOTED",
                        "SIGNED",
                        "SENT",
                        "CONFIRMED",
                        "FAILED",
                        "AMBIGUOUS",
                        "CANCELLED"
                    })
            String state,

            String failureCode,

            @Schema(pattern = "^[1-9A-HJ-NP-Za-km-z]{64,90}$")
            String txSignature,

            @Schema(pattern = "^[0-9]{1,20}$") String feeLamports,

            @Schema(allowableValues = {"BOT_WALLET", "MAIN_WALLET"})
            String feePayer,

            @Schema(format = "date-time") Instant signingDeadline,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant createdAt,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant updatedAt) {}

    @Schema(name = "RealOrderPage")
    public record RealOrderPage(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<RealOrder> items,
            String nextCursor,
            @Schema(requiredMode = RequiredMode.REQUIRED) long total) {}

    @Schema(name = "RealSigningDisplay", description = "O que a tela de assinatura mostra (AC3.2.1)")
    public record SigningDisplay(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BUY", "SELL"})
            String side,

            @Schema(requiredMode = RequiredMode.REQUIRED) TokenAmount input,
            @Schema(requiredMode = RequiredMode.REQUIRED) TokenAmount expectedOutput,
            @Schema(requiredMode = RequiredMode.REQUIRED) TokenAmount minOutput,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,20}$")
            String feeLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 500)
            String reason) {}

    @Schema(name = "RealSigningSession", description = "Sessão de assinatura do modo Assinada (R3)")
    public record SigningSession(
            @Schema(requiredMode = RequiredMode.REQUIRED) String transactionBase64,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "SHA-256 da mensagem montada")
            String messageDigest,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            Instant deadline,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    format = "date-time",
                    description = "Instante UTC do servidor ao montar a resposta (U6, BR3.2)")
            Instant serverTime,

            @Schema(requiredMode = RequiredMode.REQUIRED) SigningDisplay display) {}

    @Schema(name = "RealSignedTransactionRequest")
    public record SignedTransactionRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 1700)
            String signedTransactionBase64) {}

    @Schema(name = "RealModeRequest")
    public record ModeRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"SIGNED", "AUTO_EXITS", "AUTO_FULL"})
            String mode,

            @Schema(format = "uuid") UUID challengeId,

            @Schema(pattern = "^[1-9A-HJ-NP-Za-km-z]{64,90}$")
            String signature) {}

    @Schema(name = "RealModeView")
    public record ModeView(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"SIGNED", "AUTO_EXITS", "AUTO_FULL"})
            String mode) {}

    @Schema(name = "RealLiquidationView")
    public record LiquidationView(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID liquidationId) {}

    static RealOrder of(RealOrderView order, Map<String, Integer> decimals) {
        boolean buy = order.side() == RealSide.BUY;
        String inMint = buy ? WRAPPED_SOL : order.mint();
        String outMint = buy ? order.mint() : WRAPPED_SOL;
        long in =
                buy ? order.inputLamports().orElse(0L) : order.inputBaseUnits().orElse(0L);
        TokenAmount output = order.outBaseUnits()
                .or(order::expectedOutBaseUnits)
                .map(units -> amount(outMint, units, decimals))
                .orElse(null);
        return new RealOrder(
                order.realOrderId(),
                order.botId().orElse(null),
                order.liquidationId().orElse(null),
                order.origin(),
                order.side().name(),
                amount(inMint, in, decimals),
                output,
                order.minOutBaseUnits()
                        .map(units -> amount(outMint, units, decimals))
                        .orElse(null),
                order.operationMode(),
                order.state().name(),
                order.failureCode().map(Enum::name).orElse(null),
                order.txSignature().orElse(null),
                order.feeLamports().map(String::valueOf).orElse(null),
                order.feePayer().orElse(null),
                order.signingDeadline().orElse(null),
                order.createdAt(),
                order.updatedAt());
    }

    static TokenAmount amount(String mint, long units, Map<String, Integer> decimals) {
        return new TokenAmount(
                mint, Long.toString(units), WRAPPED_SOL.equals(mint) ? Integer.valueOf(9) : decimals.get(mint));
    }
}
