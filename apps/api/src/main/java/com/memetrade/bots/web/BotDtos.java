package com.memetrade.bots.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.bots.application.BotQueries;
import com.memetrade.bots.application.BotSummaryView;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotConfigValidator;
import com.memetrade.bots.domain.BotLimits;
import com.memetrade.bots.domain.ParameterSpec;
import com.memetrade.bots.domain.StrategyTemplate;
import com.memetrade.bots.domain.TokenSelection;
import com.memetrade.decision.infra.JdbcDecisionRepository.StoredDecision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bodies of C4 (contract-summary.md), with the U5 additions. Every decimal travels as TEXT ({@code DecimalString});
 * the request bodies reject undeclared fields ({@code fail-on-unknown-properties}, NFR6.2).
 */
final class BotDtos {

    private BotDtos() {}

    private static final ObjectMapper TREES = new ObjectMapper();

    @Schema(name = "CreateBotRequest")
    record CreateBotRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String templateId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 60)
            String name,

            @Schema(
                    allowableValues = {"PAPER", "REAL"},
                    description = "Modo do bot, fixo depois da criação (FR1.4). O app novo sempre envia; ausente vale"
                            + " PAPER, para os clientes antigos. REAL só com a elegibilidade disponível no comando")
            String market) {}

    /** 261121-paper-real-unico U1 (C2, BR2.1, BR2.2): the REAL eligibility of the screen; never authorizes trading. */
    @Schema(name = "RealEligibility")
    record RealEligibilityDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean available,

            @Schema(
                    description = "Só quando available = false: o motivo mais forte",
                    allowableValues = {"KILL_SWITCH", "REAL_DISABLED", "WALLET_INACTIVE", "NOT_RELEASED", "QUARANTINE"})
            String reason,

            @Schema(
                    format = "date-time",
                    description = "Só em QUARANTINE: o instante UTC em que a liberação passa a valer (OQ2)")
            Instant effectiveAt,

            @Schema(
                    description = "Só em WALLET_INACTIVE: a ação que a tela oferece",
                    allowableValues = {"ACTIVATE_BOT_WALLET"})
            String action) {

        static RealEligibilityDto of(com.memetrade.bots.domain.RealEligibility eligibility) {
            return new RealEligibilityDto(
                    eligibility.available(),
                    eligibility.reason().map(Enum::name).orElse(null),
                    eligibility.effectiveAt().orElse(null),
                    eligibility.action().map(Enum::name).orElse(null));
        }
    }

    @Schema(name = "TokenRef")
    record TokenDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String chain,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String mint) {}

    @Schema(name = "TokenRule")
    record RuleDto(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"LIQUIDITY", "VOLUME_24H", "HOLDERS", "AGE_MINUTES", "RISK_SCORE"})
            String field,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"GTE", "LTE"})
            String operator,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^-?\\d+(\\.\\d+)?$")
            String value) {}

    @Schema(name = "TokenSelection")
    record SelectionDto(List<TokenDto> tokens, List<RuleDto> rules) {

        BotConfigValidator.RawSelection raw() {
            return new BotConfigValidator.RawSelection(
                    tokens == null
                            ? List.of()
                            : tokens.stream()
                                    .map(t -> new BotConfigValidator.RawToken(t.chain(), t.mint()))
                                    .toList(),
                    rules == null
                            ? List.of()
                            : rules.stream()
                                    .map(r -> new BotConfigValidator.RawRule(r.field(), r.operator(), r.value()))
                                    .toList());
        }

        static SelectionDto of(TokenSelection selection) {
            return new SelectionDto(
                    selection.tokens().stream()
                            .map(t -> new TokenDto(t.chain(), t.mint()))
                            .toList(),
                    selection.rules().stream()
                            .map(r -> new RuleDto(
                                    r.field().name(),
                                    r.operator().name(),
                                    r.value().toPlainString()))
                            .toList());
        }
    }

    /** C4 {@code BotLimits}: {@code maxDrawdown} and {@code maxSlippage} are percentage points. */
    @Schema(name = "BotLimits")
    record LimitsDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String maxOrderSize,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String maxTokenExposure,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String maxTotalExposure,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String maxDailyLoss,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Percentual")
            String maxDrawdown,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Percentual")
            String maxSlippage,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String minLiquidity,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
            Long minIntervalSeconds) {

        BotConfigValidator.RawLimits raw() {
            return new BotConfigValidator.RawLimits(
                    maxOrderSize,
                    maxTokenExposure,
                    maxTotalExposure,
                    maxDailyLoss,
                    maxDrawdown,
                    maxSlippage,
                    minLiquidity,
                    minIntervalSeconds);
        }

        static LimitsDto of(BotLimits limits) {
            return new LimitsDto(
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

    @Schema(name = "DraftStepRequest")
    record DraftRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "4")
            Integer step,

            Map<String, String> parameters,
            SelectionDto tokenSelection,
            LimitsDto limits,
            String virtualBalance) {}

    @Schema(name = "StrategyParameter")
    record ParameterDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String label,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String unit,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, name = "default")
            @com.fasterxml.jackson.annotation.JsonProperty("default")
            String defaultValue,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String min,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String max,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String step) {

        static ParameterDto of(ParameterSpec spec) {
            return new ParameterDto(
                    spec.key(),
                    spec.label(),
                    spec.unit(),
                    spec.defaultValue().toPlainString(),
                    spec.min().toPlainString(),
                    spec.max().toPlainString(),
                    spec.step().toPlainString());
        }
    }

    @Schema(name = "StrategyTemplate")
    record TemplateDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String templateId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String description,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<ParameterDto> parameters) {

        static TemplateDto of(StrategyTemplate template) {
            return new TemplateDto(
                    template.templateId().name(),
                    template.name(),
                    template.description(),
                    template.version(),
                    template.parameters().stream().map(ParameterDto::of).toList());
        }
    }

    @Schema(name = "BotPage")
    record BotPageDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<BotSummaryView> items,

            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String nextCursor,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long activeCount,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int activeLimit) {}

    /** U5 addition to C4: one bot with its draft and limits, for the wizard (AC4.4.1) and the bot screen (BR6.2). */
    @Schema(name = "BotDetail")
    record BotDetailDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID botId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PAPER", "REAL"},
                    deprecated = true,
                    description = "Obsoleto: sempre igual a market (FR2.4, ADR-009). Leia market")
            String mode,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PAPER", "REAL"},
                    description = "261113-trading-real-bot U4 (U4-NFR8.5): mercado do bot, fixado na criação")
            String market,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String state,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> pauseReasons,

            Instant resumesAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int draftStep,

            Integer limitsVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String templateId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String templateVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> parameters,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            SelectionDto tokenSelection,

            String virtualBalance,
            LimitsDto limits,
            Instant activatedAt,
            Instant stoppedAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant updatedAt) {

        static BotDetailDto of(BotQueries.BotDetail detail, Instant now) {
            Bot bot = detail.bot();
            BotSummaryView summary = BotSummaryView.of(bot, now);
            Map<String, String> parameters = new LinkedHashMap<>();
            bot.parameters().forEach((key, value) -> parameters.put(key, value.toPlainString()));
            return new BotDetailDto(
                    bot.botId(),
                    bot.name(),
                    // 261121-paper-real-unico U1 (BR1.3, ADR-009): the deprecated mode is always the market.
                    bot.market().name(),
                    bot.market().name(),
                    bot.status().state().name(),
                    summary.pauseReasons().stream().map(Enum::name).toList(),
                    summary.resumesAt(),
                    bot.draftStep(),
                    bot.currentLimitsVersion().orElse(null),
                    bot.templateId().name(),
                    bot.templateVersion(),
                    parameters,
                    SelectionDto.of(bot.selection()),
                    bot.virtualBalance().map(v -> v.toPlainString()).orElse(null),
                    detail.limits().map(LimitsDto::of).orElse(null),
                    bot.activatedAt().orElse(null),
                    bot.stoppedAt().orElse(null),
                    bot.updatedAt());
        }
    }

    /** The value of a factor: KNOWN with a decimal, or UNKNOWN (never zero). */
    @Schema(name = "FactorValue")
    record FactorValueDto(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"KNOWN", "UNKNOWN"})
            String status,

            String value) {}

    /** C4 {@code Decision.factors[]} with the U5 additions {@code token} and {@code label}. */
    @Schema(name = "DecisionFactor")
    record FactorDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            TokenDto token,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FactorValueDto value,

            String threshold,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean satisfied,

            String label) {

        static FactorDto of(JsonNode node) {
            JsonNode value = node.get("value");
            return new FactorDto(
                    new TokenDto(
                            node.get("token").get("chain").asText(),
                            node.get("token").get("mint").asText()),
                    node.get("name").asText(),
                    new FactorValueDto(
                            value.get("status").asText(),
                            value.has("value") ? value.get("value").asText() : null),
                    node.has("threshold") ? node.get("threshold").asText() : null,
                    node.get("satisfied").asBoolean(),
                    node.has("label") ? node.get("label").asText() : null);
        }
    }

    @Schema(name = "DecisionSource")
    record SourceDto(String sourceId, String name, Boolean simulated) {}

    /** C4 {@code Decision.inputs[]}: a market value used by the decision, KNOWN or UNKNOWN, with its source. */
    @Schema(name = "DecisionInput")
    record InputDto(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"KNOWN", "UNKNOWN"})
            String status,

            String value,
            String reason,
            SourceDto source,
            String observedAt,
            Boolean stale) {

        static InputDto of(JsonNode node) {
            JsonNode source = node.get("source");
            return new InputDto(
                    node.get("status").asText(),
                    node.has("value") ? node.get("value").asText() : null,
                    node.has("reason") ? node.get("reason").asText() : null,
                    source == null
                            ? null
                            : new SourceDto(
                                    source.get("sourceId").asText(),
                                    source.get("name").asText(),
                                    source.get("simulated").asBoolean()),
                    node.has("observedAt") ? node.get("observedAt").asText() : null,
                    node.has("stale") ? node.get("stale").asBoolean() : null);
        }
    }

    /** C4 {@code Decision} with the U5 additions; {@code factors} and {@code inputs} as recorded. */
    @Schema(name = "Decision")
    record DecisionDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID decisionId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID cycleId,

            String chain,
            String tokenMint,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"BUY", "SELL", "HOLD", "NO_TRADE"})
            String action,

            @Schema(
                    allowableValues = {
                        "UNKNOWN_DATA",
                        "STALE_DATA",
                        "INSUFFICIENT_HISTORY",
                        "ASSESSMENT_UNAVAILABLE",
                        "NO_POSITION_TO_SELL",
                        "RISK_BAND_HIGH",
                        "NO_SIGNAL",
                        "NO_TOKENS",
                        "QUANTITY_BELOW_MINIMUM"
                    })
            String noTradeReason,

            @Schema(allowableValues = {"TAKE_PROFIT", "STOP_LOSS", "MAX_HOLD", "RANGE_EXIT"})
            String exitReason,

            String requestedQuantity,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String strategyVersion,

            /**
             * 261121-paper-real-unico U1 (BR1.9, reliability-design.md "Decisão do fundador"): {@code 0} is a
             * documented sentinel that appears only in a REAL decision and means "the REAL limits of the account": a
             * REAL bot has no limits version of its own.
             */
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Versão dos limites do bot. 0 só em decisão REAL e significa \"limites da conta\":"
                            + " um bot REAL não tem versão de limites própria (BR1.9)")
            long limitsVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<FactorDto> factors,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<InputDto> inputs,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String summary,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant validUntil,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt) {

        static DecisionDto of(StoredDecision decision) {
            List<FactorDto> factors = new ArrayList<>();
            tree(decision.factors()).forEach(node -> factors.add(FactorDto.of(node)));
            return new DecisionDto(
                    decision.decisionId(),
                    decision.cycleId(),
                    decision.chain().orElse(null),
                    decision.tokenMint().orElse(null),
                    decision.action(),
                    decision.noTradeReason().orElse(null),
                    decision.exitReason().orElse(null),
                    decision.requestedQuantity().map(q -> q.toPlainString()).orElse(null),
                    decision.strategyVersion(),
                    decision.limitsVersion(),
                    factors,
                    inputs(decision.inputs()),
                    decision.summary(),
                    decision.validUntil(),
                    decision.createdAt());
        }

        /** C4 {@code inputs}: every market value the decision used (reference prices and window buckets). */
        private static List<InputDto> inputs(String recorded) {
            List<InputDto> values = new ArrayList<>();
            for (JsonNode token : tree(recorded)) {
                if (token.has("referencePrice")) {
                    values.add(InputDto.of(token.get("referencePrice")));
                }
                for (JsonNode point : token.path("window").path("points")) {
                    values.add(InputDto.of(point.get("price")));
                    values.add(InputDto.of(point.get("volume24h")));
                }
            }
            return values;
        }

        private static JsonNode tree(String json) {
            try {
                return TREES.readTree(json);
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("A recorded decision could not be read", failure);
            }
        }
    }

    @Schema(name = "DecisionPage")
    record DecisionPageDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<DecisionDto> items,

            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String nextCursor) {}
}
