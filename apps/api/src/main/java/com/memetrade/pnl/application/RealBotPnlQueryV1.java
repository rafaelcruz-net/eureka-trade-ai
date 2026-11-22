package com.memetrade.pnl.application;

import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealModeTotal;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.real.RealPnlCalculator;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealOrderView;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 261121-paper-real-unico U1: the FIRST implementation of C10 (owner U2; BR5.5, NFR1.1, NFR2.3; performance-design.md
 * "Bloco REAL"). ONE read of the settled orders of the person (at most {@code real-max-orders}; above it the block is
 * UNAVAILABLE with {@code TOO_MANY_ORDERS}, never a partial number), the prices read once for every mint, the orders
 * grouped by bot in memory, and the average cost of today ({@link RealPnlCalculator}) per bot and once over every order
 * (the total of the mode, the same number as {@code GET /api/v1/real/pnl}). No query filters {@code real_order} by bot
 * (no index for it, no migration in the U1). The orders without a bot enter only the total.
 *
 * <p>In the U1 the realized is NET of fees and {@code total = realized + unrealized}; {@code unowned} is empty and
 * {@code shares} empty (the U2 changes the calculation and the formula). An unknown price leaves the unrealized and the
 * total unknown, never zero; the realized and the fees stay exact. Read only, no lock.
 */
@Service
public class RealBotPnlQueryV1 implements RealBotPnlQuery {

    private final RealExecutionPort execution;
    private final RealPnlService today;
    private final BotDirectory directory;
    private final int maxOrders;
    private final Clock clock;

    public RealBotPnlQueryV1(
            RealExecutionPort execution,
            RealPnlService today,
            BotDirectory directory,
            PnlByModeProperties properties,
            Clock clock) {
        this.execution = execution;
        this.today = today;
        this.directory = directory;
        this.maxOrders = properties.realMaxOrders();
        this.clock = clock;
    }

    @Override
    public RealModePnl modePnl(UUID tenantId, UUID userId, PnlWindow window) {
        requireAll(window);
        try {
            List<RealOrderView> orders = settled(tenantId, userId);
            Map<String, Optional<BigDecimal>> prices = today.lamportPrices(RealPnlService.mints(orders));
            List<RealBotPnlLine> lines = new ArrayList<>();
            List<BotEntry> bots = directory.botsOf(tenantId, userId, PnlMarket.REAL);
            for (BotEntry bot : bots) {
                lines.add(line(bot, ordersOf(orders, bot.botId()), prices));
            }
            RealPnlCalculator.Result all = RealPnlCalculator.of(RealPnlService.fills(orders), prices);
            RealModeTotal total = new RealModeTotal(
                    all.realizedLamports(), unrealized(all), all.feesLamports(), total(all), clock.instant());
            return new RealModePnl(lines, Optional.empty(), total, bots.isEmpty() && orders.isEmpty());
        } catch (DataAccessException failure) {
            throw new RealPnlUnavailable(failure.getClass().getSimpleName(), failure);
        }
    }

    @Override
    public Optional<RealBotPnlLine> botPnl(UUID tenantId, UUID userId, UUID botId, PnlWindow window) {
        requireAll(window);
        Optional<BotEntry> bot =
                directory.botOf(tenantId, userId, botId).filter(entry -> entry.market() == PnlMarket.REAL);
        if (bot.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<RealOrderView> orders = ordersOf(settled(tenantId, userId), botId);
            return Optional.of(line(bot.get(), orders, today.lamportPrices(RealPnlService.mints(orders))));
        } catch (DataAccessException failure) {
            throw new RealPnlUnavailable(failure.getClass().getSimpleName(), failure);
        }
    }

    private List<RealOrderView> settled(UUID tenantId, UUID userId) {
        List<RealOrderView> orders = execution.settledOrders(tenantId, userId, maxOrders + 1);
        if (orders.size() > maxOrders) {
            throw new RealPnlUnavailable(RealPnlUnavailable.TOO_MANY_ORDERS);
        }
        return orders;
    }

    private static List<RealOrderView> ordersOf(List<RealOrderView> orders, UUID botId) {
        return orders.stream()
                .filter(order -> order.botId().filter(botId::equals).isPresent())
                .toList();
    }

    private static RealBotPnlLine line(
            BotEntry bot, List<RealOrderView> orders, Map<String, Optional<BigDecimal>> prices) {
        RealPnlCalculator.Result result = RealPnlCalculator.of(RealPnlService.fills(orders), prices);
        return new RealBotPnlLine(
                bot.botId(),
                bot.status(),
                result.realizedLamports(),
                unrealized(result),
                result.feesLamports(),
                total(result),
                List.of());
    }

    private static LamportsValue unrealized(RealPnlCalculator.Result result) {
        return result.unrealizedLamports()
                .<LamportsValue>map(LamportsValue.Known::new)
                .orElse(new LamportsValue.Unknown(LamportsValue.UnknownReason.PRICE_UNKNOWN));
    }

    /** BR5.5 in the U1: {@code realized + unrealized} (the fees are already in both); unknown with the unrealized. */
    private static LamportsValue total(RealPnlCalculator.Result result) {
        return result.unrealizedLamports()
                .<LamportsValue>map(gain -> new LamportsValue.Known(Math.addExact(result.realizedLamports(), gain)))
                .orElse(new LamportsValue.Unknown(LamportsValue.UnknownReason.PRICE_UNKNOWN));
    }

    private static void requireAll(PnlWindow window) {
        if (!(window instanceof PnlWindow.All)) {
            throw new IllegalArgumentException("The U1 answers only the period ALL; the U3 adds RANGE");
        }
    }
}
