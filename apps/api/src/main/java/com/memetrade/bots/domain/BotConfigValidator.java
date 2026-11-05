package com.memetrade.bots.domain;

import com.memetrade.decision.domain.SelectionRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side validation of the wizard and of the limits (BR1.2 to BR1.5, NFR6.2), the same rules the screen repeats.
 * Every value arrives as TEXT and is parsed here as an exact decimal; a text that is not a decimal is refused, never
 * read as zero. Bounds are inclusive. Each refusal names its field with a short pt-BR message.
 */
public final class BotConfigValidator {

    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern BASE58 =
            Pattern.compile("^[123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{32,44}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int USDC_SCALE = 6;
    private static final int PERCENT_SCALE = 4;

    private final BigDecimal balanceMin;
    private final BigDecimal balanceCap;

    public BotConfigValidator(BigDecimal balanceMin, BigDecimal balanceCap) {
        this.balanceMin = balanceMin;
        this.balanceCap = balanceCap;
    }

    public BigDecimal balanceCap() {
        return balanceCap;
    }

    /** BR1.2: every parameter of the template, inside [min, max] and on the step grid; no unknown key. */
    public List<Violation> parameters(StrategyTemplate template, Map<String, String> raw) {
        List<Violation> violations = new ArrayList<>();
        for (String key : raw.keySet()) {
            if (template.parameter(key).isEmpty()) {
                violations.add(invalid("parameters." + key, "Parâmetro desconhecido."));
            }
        }
        for (ParameterSpec spec : template.parameters()) {
            String field = "parameters." + spec.key();
            Optional<BigDecimal> value = decimal(raw.get(spec.key()));
            if (value.isEmpty()) {
                violations.add(invalid(field, "Informe um número decimal."));
            } else if (!onGrid(spec, value.get())) {
                violations.add(invalid(
                        field,
                        "Use um valor de " + spec.min().toPlainString() + " a "
                                + spec.max().toPlainString() + ", em passos de "
                                + spec.step().toPlainString() + "."));
            }
        }
        return violations;
    }

    /** BR1.3: at least one token or one rule; up to 10 tokens without repetition and up to 5 rules. */
    public List<Violation> selection(RawSelection selection) {
        List<RawToken> tokens = selection.tokens() == null ? List.of() : selection.tokens();
        List<RawRule> rules = selection.rules() == null ? List.of() : selection.rules();
        if (tokens.isEmpty() && rules.isEmpty()) {
            return List.of(invalid("tokenSelection", "Escolha pelo menos um token ou uma regra de seleção."));
        }
        List<Violation> violations = new ArrayList<>();
        if (tokens.size() > TokenSelection.MAX_TOKENS) {
            violations.add(invalid("tokenSelection.tokens", "Escolha no máximo 10 tokens."));
        } else {
            checkTokens(tokens, violations);
        }
        if (rules.size() > TokenSelection.MAX_RULES) {
            violations.add(invalid("tokenSelection.rules", "Use no máximo 5 regras."));
        } else {
            checkRules(rules, violations);
        }
        return violations;
    }

    /** BR1.4: from the platform minimum to the cap, with 6 places; above the cap shows the cap. */
    public List<Violation> balance(String raw) {
        Optional<BigDecimal> value = decimal(raw);
        if (value.isEmpty() || value.get().scale() > USDC_SCALE) {
            return List.of(invalid("virtualBalance", "Informe um valor em USDC com até 6 casas."));
        }
        if (value.get().compareTo(balanceCap) > 0) {
            return List.of(new Violation(
                    "virtualBalance",
                    Violation.Code.VIRTUAL_BALANCE_ABOVE_CAP,
                    "O saldo virtual máximo é " + brl(balanceCap) + " USDC."));
        }
        if (value.get().compareTo(balanceMin) < 0) {
            return List.of(invalid("virtualBalance", "O saldo virtual mínimo é " + brl(balanceMin) + " USDC."));
        }
        return List.of();
    }

    /** BR1.5: all required, positive, percentages in (0, 100], and order <= token exposure <= total exposure. */
    public List<Violation> limits(RawLimits raw) {
        List<Violation> violations = new ArrayList<>();
        Optional<BigDecimal> order = positive(raw.maxOrderSize(), "maxOrderSize", USDC_SCALE, violations);
        Optional<BigDecimal> token = positive(raw.maxTokenExposure(), "maxTokenExposure", USDC_SCALE, violations);
        Optional<BigDecimal> total = positive(raw.maxTotalExposure(), "maxTotalExposure", USDC_SCALE, violations);
        positive(raw.maxDailyLoss(), "maxDailyLoss", USDC_SCALE, violations);
        percent(raw.maxDrawdown(), "maxDrawdown", violations);
        percent(raw.maxSlippage(), "maxSlippage", violations);
        Optional<BigDecimal> liquidity = decimal(raw.minLiquidity());
        if (liquidity.isEmpty()
                || liquidity.get().signum() < 0
                || liquidity.get().scale() > USDC_SCALE) {
            violations.add(invalid("minLiquidity", "Informe um valor em USDC igual ou maior que zero."));
        }
        if (raw.minIntervalSeconds() == null || raw.minIntervalSeconds() < 1) {
            violations.add(invalid("minIntervalSeconds", "Informe pelo menos 1 segundo."));
        }
        if (order.isPresent() && token.isPresent() && order.get().compareTo(token.get()) > 0) {
            violations.add(incoherent("maxOrderSize", "O tamanho por ordem não pode passar da exposição por token."));
        }
        if (token.isPresent() && total.isPresent() && token.get().compareTo(total.get()) > 0) {
            violations.add(incoherent("maxTokenExposure", "A exposição por token não pode passar da exposição total."));
        }
        return violations;
    }

    /** The limits of a valid {@link RawLimits} (call {@link #limits} first). */
    public static BotLimits toLimits(RawLimits raw) {
        return new BotLimits(
                usdc(raw.maxOrderSize()),
                usdc(raw.maxTokenExposure()),
                usdc(raw.maxTotalExposure()),
                usdc(raw.maxDailyLoss()),
                pct(raw.maxDrawdown()),
                pct(raw.maxSlippage()),
                usdc(raw.minLiquidity()),
                raw.minIntervalSeconds());
    }

    /** The selection of a valid {@link RawSelection} (call {@link #selection} first). */
    public static TokenSelection toSelection(RawSelection raw) {
        List<com.memetrade.marketdata.api.TokenId> tokens = raw.tokens() == null
                ? List.of()
                : raw.tokens().stream()
                        .map(t -> new com.memetrade.marketdata.api.TokenId(t.chain(), t.mint()))
                        .toList();
        List<SelectionRule> rules = raw.rules() == null
                ? List.of()
                : raw.rules().stream()
                        .map(r -> new SelectionRule(
                                SelectionRule.Field.valueOf(r.field()),
                                SelectionRule.Operator.valueOf(r.operator()),
                                new BigDecimal(r.value())))
                        .toList();
        return new TokenSelection(tokens, rules);
    }

    /** The selection as received, decimals still as text. */
    public record RawSelection(List<RawToken> tokens, List<RawRule> rules) {}

    public record RawToken(String chain, String mint) {}

    public record RawRule(String field, String operator, String value) {}

    /** The limits as received, decimals still as text (C4 names: maxDrawdown and maxSlippage are percentages). */
    public record RawLimits(
            String maxOrderSize,
            String maxTokenExposure,
            String maxTotalExposure,
            String maxDailyLoss,
            String maxDrawdown,
            String maxSlippage,
            String minLiquidity,
            Long minIntervalSeconds) {

        public static RawLimits of(BotLimits limits) {
            return new RawLimits(
                    limits.maxOrderSize().toPlainString(),
                    limits.maxTokenExposure().toPlainString(),
                    limits.maxTotalExposure().toPlainString(),
                    limits.maxDailyLoss().toPlainString(),
                    limits.maxDrawdownPct().toPlainString(),
                    limits.maxSlippagePct().toPlainString(),
                    limits.minLiquidity().toPlainString(),
                    limits.minIntervalSeconds());
        }
    }

    private static boolean onGrid(ParameterSpec spec, BigDecimal value) {
        if (value.compareTo(spec.min()) < 0 || value.compareTo(spec.max()) > 0) {
            return false;
        }
        return value.subtract(spec.min()).remainder(spec.step()).signum() == 0;
    }

    private static void checkTokens(List<RawToken> tokens, List<Violation> violations) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            RawToken token = tokens.get(i);
            String field = "tokenSelection.tokens[" + i + "]";
            if (token == null || !"solana".equals(token.chain())) {
                violations.add(invalid(field + ".chain", "Só a rede solana está disponível."));
            } else if (token.mint() == null || !BASE58.matcher(token.mint()).matches()) {
                violations.add(invalid(field + ".mint", "Endereço do token inválido."));
            } else if (!seen.add(token.chain() + ":" + token.mint())) {
                violations.add(invalid(field, "Token repetido na lista."));
            }
        }
    }

    private static void checkRules(List<RawRule> rules, List<Violation> violations) {
        for (int i = 0; i < rules.size(); i++) {
            RawRule rule = rules.get(i);
            String field = "tokenSelection.rules[" + i + "]";
            if (rule == null || !isEnum(SelectionRule.Field.class, rule.field())) {
                violations.add(invalid(field + ".field", "Campo de filtro inválido."));
            } else if (!isEnum(SelectionRule.Operator.class, rule.operator())) {
                violations.add(invalid(field + ".operator", "Use maior ou igual (GTE) ou menor ou igual (LTE)."));
            } else if (decimal(rule.value()).filter(v -> v.signum() >= 0).isEmpty()) {
                violations.add(invalid(field + ".value", "Informe um número decimal não negativo."));
            }
        }
    }

    private static <E extends Enum<E>> boolean isEnum(Class<E> type, String value) {
        if (value == null) {
            return false;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<BigDecimal> positive(String raw, String field, int scale, List<Violation> violations) {
        Optional<BigDecimal> value = decimal(raw);
        if (value.isEmpty() || value.get().signum() <= 0 || value.get().scale() > scale) {
            violations.add(invalid(field, "Informe um valor maior que zero."));
            return Optional.empty();
        }
        return value;
    }

    private static void percent(String raw, String field, List<Violation> violations) {
        Optional<BigDecimal> value = decimal(raw);
        if (value.isEmpty()
                || value.get().signum() <= 0
                || value.get().compareTo(HUNDRED) > 0
                || value.get().scale() > PERCENT_SCALE) {
            violations.add(invalid(field, "Informe um percentual maior que 0 e até 100."));
        }
    }

    private static Optional<BigDecimal> decimal(String raw) {
        if (raw == null || !DECIMAL.matcher(raw.trim()).matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(raw.trim()));
    }

    private static BigDecimal usdc(String raw) {
        return new BigDecimal(raw.trim()).setScale(USDC_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal pct(String raw) {
        return new BigDecimal(raw.trim()).setScale(PERCENT_SCALE, RoundingMode.UNNECESSARY);
    }

    private static String brl(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format.format(value);
    }

    private static Violation invalid(String field, String message) {
        return new Violation(field, Violation.Code.VALIDATION_FAILED, message);
    }

    private static Violation incoherent(String field, String message) {
        return new Violation(field, Violation.Code.LIMIT_INCOHERENT, message);
    }
}
