package com.memetrade.bots.web;

import com.memetrade.bots.application.BotSummaryView;
import com.memetrade.marketdata.api.SourceStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/**
 * The {@code data} of each event of the live channel C7 (functional-spec.md of U9, "Formato dos eventos do canal"):
 * JSON of one of these envelopes. They document the format the {@link DashboardMarkPoller} already sent before U9 (same
 * field names and values), so the guest app generates their types from the OpenAPI instead of writing them by hand
 * (team.md). Instants are UTC ISO-8601 text.
 */
public final class DashboardEvents {

    private DashboardEvents() {}

    /** {@code bot.state}: at most 100 bots of the person in any state, newest change first, and the active count. */
    @Schema(
            name = "DashboardBotStateEvent",
            description =
                    "data do evento bot.state: até 100 bots da pessoa, de qualquer estado, mais recentes primeiro;"
                            + " sem activeLimit nem contagens por estado")
    public record BotState(
            @ArraySchema(
                    schema = @Schema(implementation = BotSummaryView.class),
                    arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
            List<BotSummaryView> items,

            @Schema(requiredMode = RequiredMode.REQUIRED) long activeCount) {}

    /** One order of {@code trade.recorded}: no prices and no reason of a not filled order. */
    @Schema(name = "DashboardTradeNotice", description = "Ordem simulada nova, sem preços nem notFilledReason")
    public record TradeNotice(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID orderId,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid")
            UUID botId,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"BUY", "SELL"})
            String side,

            @Schema(requiredMode = RequiredMode.REQUIRED) String chain,
            @Schema(requiredMode = RequiredMode.REQUIRED) String tokenMint,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"FILLED", "PARTIALLY_FILLED", "NOT_FILLED", "CLOSE_PENDING"})
            String status,

            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            String createdAt) {}

    /** {@code trade.recorded}: the orders recorded since the last one this channel sent. */
    @Schema(name = "DashboardTradeRecordedEvent", description = "data do evento trade.recorded")
    public record TradeRecorded(
            @ArraySchema(arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
            List<TradeNotice> items) {}

    /** {@code pnl.updated}: the ledger mark changed; the app reads the P&L again. */
    @Schema(name = "DashboardPnlUpdatedEvent", description = "data do evento pnl.updated")
    public record PnlUpdated(
            @Schema(requiredMode = RequiredMode.REQUIRED) long ledgerMark) {}

    /** {@code data.status}: the state of every market source (C2 {@code SourceStatus}). */
    @Schema(name = "DashboardDataStatusEvent", description = "data do evento data.status")
    public record DataStatus(
            @ArraySchema(
                    schema = @Schema(ref = "#/components/schemas/SourceStatus"),
                    arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
            List<SourceStatus> items) {}

    /** {@code heartbeat}: every 15 s; only proves the channel is alive. */
    @Schema(name = "DashboardHeartbeatEvent", description = "data do evento heartbeat")
    public record Heartbeat(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            String at) {}

    /** {@code auth.expired}: the access token expired; the channel closes right after. */
    @Schema(name = "DashboardAuthExpiredEvent", description = "data do evento auth.expired")
    public record AuthExpired(
            @Schema(requiredMode = RequiredMode.REQUIRED, format = "date-time")
            String expiredAt) {}
}
