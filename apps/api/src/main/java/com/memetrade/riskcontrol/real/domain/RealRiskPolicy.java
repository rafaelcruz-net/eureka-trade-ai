package com.memetrade.riskcontrol.real.domain;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.OrderOrigin;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 261113-trading-real-bot U3: the pure REAL risk policy (rules.md BR1.1 to BR5.3, BR7.2). No I/O, no clock: every input
 * comes in {@link RealGateInputs}. The checks run in the fixed order of BR1.3 and the first refusal ends the evaluation:
 *
 * <ol>
 *   <li>BR1.1 state of the wallet, BR1.2 kind of the market source (buys and sells);
 *   <li>BR2.1 assessment, BR2.2 block list (buys only from here on);
 *   <li>BR3.1 price, liquidity and 24 h volume of the token known and at most {@code signal-max-age} old, then BR2.4
 *       the price of every open position;
 *   <li>BR4.1 size of the order, BR4.2 approved buys of the bot today;
 *   <li>BR4.3 loss of the day (with the photo of BR4.4), which also pauses the wallet;
 *   <li>BR5.1 cap and free balance.
 * </ol>
 *
 * Lamports and base units are {@code long} with overflow checks; prices are exact decimals; percentages are basis points;
 * limits and position values are rounded DOWN, against the buy (BR7.2). An unknown value is never taken as zero.
 */
public final class RealRiskPolicy {

    static final long BPS = 10_000;

    /** BR1.1: the pause reported when several are active, the most severe first. */
    static final List<RealPauseReason> SEVERITY = List.of(
            RealPauseReason.KILL_SWITCH,
            RealPauseReason.DIVERGENCE,
            RealPauseReason.ADMIN,
            RealPauseReason.DAILY_LOSS,
            RealPauseReason.UPSTREAM,
            RealPauseReason.USER);

    /** BR1.1: the only pauses that also stop the liquidation asked by the person. */
    static final Set<RealPauseReason> STOP_LIQUIDATION =
            Set.of(RealPauseReason.KILL_SWITCH, RealPauseReason.DIVERGENCE);

    /** The full evaluation of one order. */
    public RealVerdict evaluate(RealGateInputs inputs) {
        Optional<RealVerdict> refused = stateAndSource(inputs.intent(), inputs.wallet(), inputs.source());
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!inputs.intent().isBuy()) {
            // BR1.3: a sell that passes the state and the source is approved without the other checks (BR3.2).
            return RealVerdict.approve(Optional.empty());
        }
        BuyInputs buy = inputs.buy().orElseThrow(() -> new IllegalArgumentException("a buy needs its inputs"));
        return evaluateBuy(inputs, inputs.wallet().orElseThrow(), buy);
    }

    /**
     * BR1.1 and BR1.2, the only checks of a sell; the gate calls them first so a refused order reads nothing else.
     *
     * @return the refusal, or empty when the order may go on
     */
    public Optional<RealVerdict> stateAndSource(OrderIntent intent, Optional<RealStateView> wallet, SourceKind source) {
        if (wallet.isEmpty()) {
            return paused(RealRejectionDetail.NO_BOT_WALLET);
        }
        RealStateView state = wallet.get();
        if (!state.realEnabled()) {
            return paused(RealRejectionDetail.REAL_DISABLED);
        }
        if (!state.released()) {
            return paused(RealRejectionDetail.NOT_RELEASED);
        }
        Set<RealPauseReason> blocking = intent.origin() == OrderOrigin.USER_LIQUIDATION
                ? state.pausedReasons().stream()
                        .filter(STOP_LIQUIDATION::contains)
                        .collect(java.util.stream.Collectors.toSet())
                : state.pausedReasons();
        for (RealPauseReason reason : SEVERITY) {
            if (blocking.contains(reason)) {
                return paused(RealRejectionDetail.valueOf("PAUSED_" + reason.name()));
            }
        }
        if (source != SourceKind.REAL) {
            return Optional.of(
                    RealVerdict.reject(RealRejectionCode.SIMULATED_SOURCE_ACTIVE, RealRejectionDetail.NONE, Map.of()));
        }
        return Optional.empty();
    }

    private RealVerdict evaluateBuy(RealGateInputs inputs, RealStateView wallet, BuyInputs buy) {
        RealRiskLimits limits = inputs.limits();
        if (buy.assessment() instanceof AssessmentResult.Unavailable) {
            return reject(RealRejectionCode.TOKEN_NOT_ELIGIBLE, RealRejectionDetail.NO_ASSESSMENT);
        }
        if (buy.blocked()) {
            return reject(RealRejectionCode.TOKEN_NOT_ELIGIBLE, RealRejectionDetail.BLOCKED);
        }
        Optional<RealVerdict> signals = signals(inputs.now(), limits.signalMaxAge(), buy);
        if (signals.isPresent()) {
            return signals.get();
        }
        Optional<RealVerdict> positions = positionPrices(inputs.now(), limits.signalMaxAge(), buy.positions());
        if (positions.isPresent()) {
            return positions.get();
        }
        long input = inputs.intent().inputLamports();
        long cap = wallet.capLamports();
        long orderLimit = Math.multiplyExact(cap, limits.orderMaxBps()) / BPS;
        if (input > orderLimit) {
            return RealVerdict.reject(
                    RealRejectionCode.REAL_LIMIT_EXCEEDED,
                    RealRejectionDetail.ORDER_SIZE,
                    figures("inputLamports", input, "limitLamports", orderLimit, "capLamports", cap));
        }
        if (buy.approvedBuysToday() >= limits.dailyBuysMax()) {
            return RealVerdict.reject(
                    RealRejectionCode.REAL_LIMIT_EXCEEDED,
                    RealRejectionDetail.DAILY_ORDER_COUNT,
                    figures("approvedBuysToday", buy.approvedBuysToday(), "dailyBuysMax", limits.dailyBuysMax()));
        }
        long positionsValue = positionsValue(buy.positions());
        long currentValue = currentValue(buy.balance(), positionsValue);
        Optional<DaySnapshot> photo = Optional.empty();
        if (buy.snapshot().isPresent()) {
            DailyLoss loss = dailyLoss(buy.snapshot().get(), buy.flowsSinceSnapshot(), currentValue, cap, limits);
            if (loss.breached()) {
                return RealVerdict.reject(
                                RealRejectionCode.REAL_LIMIT_EXCEEDED,
                                RealRejectionDetail.DAILY_LOSS,
                                figures(
                                        "lossLamports",
                                        loss.lossLamports(),
                                        "baseLamports",
                                        loss.baseLamports(),
                                        "dailyLossMaxBps",
                                        limits.dailyLossMaxBps()))
                        .pausingForDailyLoss();
            }
        } else {
            // BR4.4: the first buy of the day without a photo records it with the value and the cap of this moment;
            // measured against itself, the loss is zero.
            photo = Optional.of(new DaySnapshot(
                    inputs.intent().tenantId(),
                    wallet.botWalletId(),
                    LocalDate.ofInstant(inputs.now(), ZoneOffset.UTC),
                    currentValue,
                    cap,
                    inputs.now(),
                    DaySnapshot.TakenBy.FIRST_EVALUATION));
        }
        RealBalance balance = buy.balance();
        long managed = Math.addExact(
                Math.addExact(positionsValue, balance.orderReservedLamports()), buy.openGateReservedLamports());
        if (Math.addExact(managed, input) > cap) {
            return RealVerdict.reject(
                            RealRejectionCode.REAL_CAP_EXCEEDED,
                            RealRejectionDetail.CAP,
                            figures("managedLamports", managed, "inputLamports", input, "capLamports", cap))
                    .withPhoto(photo);
        }
        long available = Math.subtractExact(
                Math.subtractExact(balance.freeLamports(), wallet.feeReserveLamports()),
                buy.openGateReservedLamports());
        if (input > available) {
            return RealVerdict.reject(
                            RealRejectionCode.REAL_CAP_EXCEEDED,
                            RealRejectionDetail.FREE_BALANCE,
                            figures("inputLamports", input, "availableLamports", available))
                    .withPhoto(photo);
        }
        return RealVerdict.approve(photo);
    }

    /** BR3.1: every signal of the token known first, then each one at most {@code maxAge} old. */
    private static Optional<RealVerdict> signals(Instant now, Duration maxAge, BuyInputs buy) {
        List<MarketValue> values = List.of(buy.price(), buy.liquidity(), buy.volume24h());
        for (MarketValue value : values) {
            if (!(value instanceof MarketValue.Known)) {
                return Optional.of(reject(RealRejectionCode.STALE_DATA, RealRejectionDetail.SIGNAL_UNKNOWN));
            }
        }
        for (MarketValue value : values) {
            long age = Duration.between(((MarketValue.Known) value).observedAt(), now)
                    .toSeconds();
            if (age > maxAge.toSeconds()) {
                return Optional.of(RealVerdict.reject(
                        RealRejectionCode.STALE_DATA,
                        RealRejectionDetail.SIGNAL_STALE,
                        figures("ageSeconds", age, "maxAgeSeconds", maxAge.toSeconds())));
            }
        }
        return Optional.empty();
    }

    /** BR2.4: an open position with an unknown or stale price blocks every new buy, naming that position. */
    private static Optional<RealVerdict> positionPrices(Instant now, Duration maxAge, List<PricedPosition> positions) {
        for (PricedPosition position : positions) {
            boolean usable = position.price() instanceof LamportPrice.Known known
                    && Duration.between(known.observedAt(), now).toSeconds() <= maxAge.toSeconds();
            if (!usable) {
                return Optional.of(reject(RealRejectionCode.STALE_DATA, RealRejectionDetail.POSITION_PRICE_UNKNOWN)
                        .withMint(position.mint()));
            }
        }
        return Optional.empty();
    }

    /**
     * BR4.3: {@code loss = start + deposited - withdrawn - current}; {@code base = min(start cap, cap now)}, so a lower
     * cap counts at once and a higher one only tomorrow. With a base of zero the check does not apply. The loss is
     * breached when it is positive and {@code loss x 10000 >= base x daily-loss-max-bps}.
     */
    public DailyLoss dailyLoss(
            DaySnapshot snapshot, RealFlows flows, long currentValue, long capNow, RealRiskLimits limits) {
        long base = Math.min(snapshot.startCapLamports(), capNow);
        long loss = Math.subtractExact(
                Math.subtractExact(
                        Math.addExact(snapshot.startValueLamports(), flows.depositedLamports()),
                        flows.withdrawnLamports()),
                currentValue);
        boolean breached = base > 0
                && loss > 0
                && Math.multiplyExact(loss, BPS) >= Math.multiplyExact(base, limits.dailyLossMaxBps());
        return new DailyLoss(loss, base, breached);
    }

    /** The loss of the day of one wallet, its base and whether it reached the limit. */
    public record DailyLoss(long lossLamports, long baseLamports, boolean breached) {}

    /**
     * BR5.2: a position is worth {@code floor(base units x lamports per base unit)} at its quote, stale or not, and its
     * cost when the quote is unknown; never zero for a missing quote.
     */
    public static long positionsValue(List<PricedPosition> positions) {
        long total = 0;
        for (PricedPosition position : positions) {
            long value = position.price() instanceof LamportPrice.Known known
                    ? BigDecimal.valueOf(position.tokenBaseUnits())
                            .multiply(known.lamportsPerBaseUnit())
                            .setScale(0, RoundingMode.FLOOR)
                            .longValueExact()
                    : position.costLamports();
            total = Math.addExact(total, value);
        }
        return total;
    }

    /** BR4.3: the total of the REAL balance (reserved lamports included) plus the value of the positions. */
    public static long currentValue(RealBalance balance, long positionsValue) {
        return Math.addExact(balance.totalLamports(), positionsValue);
    }

    private static Optional<RealVerdict> paused(RealRejectionDetail detail) {
        return Optional.of(reject(RealRejectionCode.REAL_PAUSED, detail));
    }

    private static RealVerdict reject(RealRejectionCode code, RealRejectionDetail detail) {
        return RealVerdict.reject(code, detail, Map.of());
    }

    private static Map<String, Long> figures(Object... pairs) {
        Map<String, Long> figures = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            figures.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return figures;
    }
}
