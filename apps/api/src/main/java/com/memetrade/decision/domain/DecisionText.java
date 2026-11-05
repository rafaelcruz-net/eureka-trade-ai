package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The simple pt-BR sentence of a decision (BR4.10): the action and its main reason, never a promise of profit
 * (project.md). Decimals are written with a comma, as text; a token is named by the start of its mint.
 */
final class DecisionText {

    private DecisionText() {}

    static String buy(StrategyId strategy, TokenId token, List<Factor> factors, BigDecimal windowMinutes) {
        if (strategy == StrategyId.MOMENTUM_VOLUME) {
            return "Comprar " + name(token) + ": volume " + signed(value(factors, "volumeGrowthPct")) + "% e preço "
                    + signed(value(factors, "priceRisePct")) + "% em " + windowMinutes.toPlainString() + " min";
        }
        return "Comprar " + name(token) + ": preço passou da máxima da faixa de " + windowMinutes.toPlainString()
                + " min mais a margem";
    }

    static String sell(TokenId token, ExitReason exit, List<Factor> factors) {
        String why =
                switch (exit) {
                    case TAKE_PROFIT -> "alvo de lucro atingido (" + signed(value(factors, "pnlPct")) + "%)";
                    case STOP_LOSS -> "limite de perda atingido (" + signed(value(factors, "pnlPct")) + "%)";
                    case MAX_HOLD -> "tempo máximo da posição atingido";
                    case RANGE_EXIT -> "preço voltou abaixo da mínima da faixa";
                };
        return "Vender " + name(token) + ": " + why;
    }

    static String hold(TokenId token) {
        return "Manter " + name(token) + ": nenhuma saída nem compra neste ciclo";
    }

    static String noTrade(NoTradeReason reason, Optional<TokenId> token) {
        String on = token.map(t -> " em " + name(t)).orElse("");
        String why =
                switch (reason) {
                    case UNKNOWN_DATA -> "dado de mercado desconhecido (unknown)" + on;
                    case STALE_DATA -> "dado de mercado desatualizado" + on;
                    case INSUFFICIENT_HISTORY -> "histórico insuficiente para a janela" + on;
                    case ASSESSMENT_UNAVAILABLE -> "avaliação de risco indisponível" + on;
                    case NO_POSITION_TO_SELL -> "não há posição para vender" + on;
                    case RISK_BAND_HIGH -> "faixa de risco alta" + on;
                    case NO_SIGNAL -> "nenhum sinal de compra";
                    case NO_TOKENS -> "nenhum token na seleção";
                    case QUANTITY_BELOW_MINIMUM -> "quantidade de compra abaixo do mínimo" + on;
                };
        return "Não operar: " + why;
    }

    private static String name(TokenId token) {
        String mint = token.mint();
        return mint.length() <= 6 ? mint : mint.substring(0, 6) + "…";
    }

    private static Optional<BigDecimal> value(List<Factor> factors, String name) {
        return factors.stream()
                .filter(f -> f.name().equals(name))
                .map(Factor::value)
                .filter(FactorValue.Known.class::isInstance)
                .map(v -> ((FactorValue.Known) v).value())
                .findFirst();
    }

    private static String signed(Optional<BigDecimal> value) {
        if (value.isEmpty()) {
            return "?";
        }
        String text = value.get().toPlainString().replace('.', ',');
        return value.get().signum() >= 0 ? "+" + text : text;
    }
}
