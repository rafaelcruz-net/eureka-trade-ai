package com.memetrade.riskcontrol.web;

import com.memetrade.riskcontrol.api.RealAccountLimitsView;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * 261121-paper-real-unico U1, C3 (BR3.1, BR3.2; rules.md "Ajustes de contrato que a U1 faz como dona"): the four REAL
 * limits of the account. Lamports travel as integer text, percentages as exact decimal text; a limit without a value is
 * {@code UNKNOWN} with {@code NOT_DEFINED}, never a zero. The count of daily buys is always known.
 */
public final class RealAccountLimitsDtos {

    static final String LAMPORTS = "^[0-9]{1,20}$";
    static final String PERCENT = "^\\d+(\\.\\d+)?$";

    private RealAccountLimitsDtos() {}

    @Schema(name = "RealAccountLimitsView", description = "Limites REAL da conta, só leitura (C3, BR3.1)")
    public record RealAccountLimitsDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "SOL")
            String currency,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Teto da carteira do bot")
            LimitLamportsDto cap,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Tamanho máximo por ordem: floor(teto x orderMaxBps / 10000) e o percentual do teto")
            LimitShareDto orderMax,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Compras aprovadas por bot, por dia UTC")
            LimitCountDto dailyBuys,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Perda máxima do dia sobre a base min(teto no início do dia UTC, teto atual), a"
                            + " mesma da barreira, e o percentual")
            LimitShareDto dailyLoss) {

        static RealAccountLimitsDto of(RealAccountLimitsView view) {
            return new RealAccountLimitsDto(
                    "SOL",
                    LimitLamportsDto.of(view.cap()),
                    LimitShareDto.of(view.orderMax()),
                    new LimitCountDto("KNOWN", view.dailyBuys()),
                    LimitShareDto.of(view.dailyLoss()));
        }
    }

    @Schema(
            name = "LimitLamports",
            oneOf = {LimitLamportsKnownDto.class, LimitUnknownDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "KNOWN", schema = LimitLamportsKnownDto.class),
                @DiscriminatorMapping(value = "UNKNOWN", schema = LimitUnknownDto.class)
            })
    public sealed interface LimitLamportsDto permits LimitLamportsKnownDto, LimitUnknownDto {
        static LimitLamportsDto of(RealAccountLimitsView.Amount amount) {
            return switch (amount) {
                case RealAccountLimitsView.Amount.Known known ->
                    new LimitLamportsKnownDto("KNOWN", Long.toString(known.lamports()));
                case RealAccountLimitsView.Amount.NotDefined notDefined -> LimitUnknownDto.NOT_DEFINED;
            };
        }
    }

    @Schema(
            name = "LimitShare",
            oneOf = {LimitShareKnownDto.class, LimitUnknownDto.class},
            discriminatorProperty = "status",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "KNOWN", schema = LimitShareKnownDto.class),
                @DiscriminatorMapping(value = "UNKNOWN", schema = LimitUnknownDto.class)
            })
    public sealed interface LimitShareDto permits LimitShareKnownDto, LimitUnknownDto {
        static LimitShareDto of(RealAccountLimitsView.Amount amount) {
            return switch (amount) {
                case RealAccountLimitsView.Amount.Known known ->
                    new LimitShareKnownDto(
                            "KNOWN",
                            Long.toString(known.lamports()),
                            known.percentOfCap().toPlainString());
                case RealAccountLimitsView.Amount.NotDefined notDefined -> LimitUnknownDto.NOT_DEFINED;
            };
        }
    }

    @Schema(name = "LimitLamportsKnown")
    public record LimitLamportsKnownDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS, description = "Lamports, texto inteiro")
            String value)
            implements LimitLamportsDto {}

    @Schema(name = "LimitShareKnown")
    public record LimitShareKnownDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = LAMPORTS, description = "Lamports, texto inteiro")
            String value,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    pattern = PERCENT,
                    description = "Percentual do teto em decimal exato")
            String percentOfCap)
            implements LimitShareDto {}

    @Schema(name = "LimitUnknown", description = "Limite sem valor: \"não definido\", nunca zero (BR3.2)")
    public record LimitUnknownDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "UNKNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "NOT_DEFINED")
            String reason)
            implements LimitLamportsDto, LimitShareDto {

        static final LimitUnknownDto NOT_DEFINED = new LimitUnknownDto("UNKNOWN", "NOT_DEFINED");
    }

    @Schema(name = "LimitCount", description = "Limite em contagem; sempre conhecido")
    public record LimitCountDto(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "KNOWN")
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0")
            int count) {}
}
