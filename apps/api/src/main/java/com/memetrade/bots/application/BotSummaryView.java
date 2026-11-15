package com.memetrade.bots.application;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotStateMachine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * C4 {@code BotSummary}: what the list, the commands and the live channel show of a bot. {@code resumesAt} is the next
 * UTC midnight when DAILY_LOSS is the only reason (BR6.1); {@code limitsVersion} exists from VALIDATED on.
 */
@io.swagger.v3.oas.annotations.media.Schema(name = "BotSummary")
public record BotSummaryView(
        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        UUID botId,

        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        String name,

        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                allowableValues = {"PAPER"})
        String mode,

        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        BotState state,

        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        List<PauseReason> pauseReasons,

        Instant resumesAt,
        Integer draftStep,
        Integer limitsVersion,
        String templateId,
        Instant updatedAt,

        @io.swagger.v3.oas.annotations.media.Schema(
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                allowableValues = {"PAPER", "REAL"},
                description = "261113-trading-real-bot U4 (BR1.1): mercado do bot, fixo desde a criação")
        String market) {

    public static BotSummaryView of(Bot bot, Instant now) {
        return new BotSummaryView(
                bot.botId(),
                bot.name(),
                "PAPER",
                bot.status().state(),
                bot.status().orderedReasons(),
                BotStateMachine.resumesAt(bot.status(), now).orElse(null),
                bot.draftStep(),
                bot.currentLimitsVersion().orElse(null),
                bot.templateId().name(),
                bot.updatedAt(),
                bot.market().name());
    }
}
