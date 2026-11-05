package com.memetrade.bots.domain;

import com.memetrade.decision.domain.StrategyId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The v1 template strategies of the beta (BR4.3 to BR4.6), the same rows the migration {@code 0010} seeds
 * ({@code BotSchemaIT} compares both). A new version would be valid only for bots created after it.
 */
public final class StrategyTemplates {

    public static final String V1 = "v1";

    public static final String WINDOW_MINUTES = "windowMinutes";
    public static final String VOLUME_GROWTH_MIN_PCT = "volumeGrowthMinPct";
    public static final String PRICE_RISE_MIN_PCT = "priceRiseMinPct";
    public static final String BREAKOUT_MARGIN_PCT = "breakoutMarginPct";
    public static final String TAKE_PROFIT_PCT = "takeProfitPct";
    public static final String STOP_LOSS_PCT = "stopLossPct";
    public static final String MAX_HOLD_MINUTES = "maxHoldMinutes";
    public static final String BUY_FRACTION_PCT = "buyFractionPct";

    private static final List<ParameterSpec> EXITS_AND_SIZE = List.of(
            spec(TAKE_PROFIT_PCT, "Alvo de lucro (take profit)", "%", "10.0000", "1.0000", "100.0000", "0.5000"),
            spec(STOP_LOSS_PCT, "Limite de perda (stop loss)", "%", "5.0000", "1.0000", "50.0000", "0.5000"),
            spec(MAX_HOLD_MINUTES, "Tempo máximo da posição", "min", "240", "15", "1440", "15"),
            spec(BUY_FRACTION_PCT, "Fração do saldo por compra", "%", "10.0000", "1.0000", "100.0000", "1.0000"));

    public static final StrategyTemplate MOMENTUM_VOLUME_V1 = new StrategyTemplate(
            StrategyId.MOMENTUM_VOLUME,
            V1,
            "Momentum de volume",
            "Compra quando o volume de 24 h e o preço sobem pelo menos o mínimo dentro da janela. Com o padrão de 20%,"
                    + " o volume da janela precisa ser perto de um quinto do dia, então o bot compra raramente.",
            concat(
                    List.of(
                            spec(WINDOW_MINUTES, "Janela", "min", "15", "10", "60", "5"),
                            spec(
                                    VOLUME_GROWTH_MIN_PCT,
                                    "Crescimento mínimo do volume de 24 h",
                                    "%",
                                    "20.0000",
                                    "5.0000",
                                    "200.0000",
                                    "1.0000"),
                            spec(
                                    PRICE_RISE_MIN_PCT,
                                    "Alta mínima do preço",
                                    "%",
                                    "2.0000",
                                    "0.5000",
                                    "20.0000",
                                    "0.5000")),
                    EXITS_AND_SIZE));

    public static final StrategyTemplate RANGE_BREAKOUT_V1 = new StrategyTemplate(
            StrategyId.RANGE_BREAKOUT,
            V1,
            "Rompimento de faixa",
            "Compra quando o último preço passa da máxima da faixa da janela mais a margem; vende ao voltar abaixo da"
                    + " mínima da faixa.",
            concat(
                    List.of(
                            spec(WINDOW_MINUTES, "Janela da faixa", "min", "120", "30", "720", "30"),
                            spec(
                                    BREAKOUT_MARGIN_PCT,
                                    "Margem do rompimento",
                                    "%",
                                    "1.0000",
                                    "0.1000",
                                    "10.0000",
                                    "0.1000")),
                    EXITS_AND_SIZE));

    public static final List<StrategyTemplate> ALL = List.of(MOMENTUM_VOLUME_V1, RANGE_BREAKOUT_V1);

    private StrategyTemplates() {}

    public static Optional<StrategyTemplate> find(String templateId) {
        return ALL.stream()
                .filter(template -> template.templateId().name().equals(templateId))
                .findFirst();
    }

    public static StrategyTemplate of(StrategyId templateId) {
        return ALL.stream()
                .filter(template -> template.templateId() == templateId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown template " + templateId));
    }

    private static ParameterSpec spec(
            String key, String label, String unit, String value, String min, String max, String step) {
        return new ParameterSpec(
                key,
                label,
                unit,
                new BigDecimal(value),
                new BigDecimal(min),
                new BigDecimal(max),
                new BigDecimal(step));
    }

    private static List<ParameterSpec> concat(List<ParameterSpec> own, List<ParameterSpec> common) {
        return java.util.stream.Stream.concat(own.stream(), common.stream()).toList();
    }
}
