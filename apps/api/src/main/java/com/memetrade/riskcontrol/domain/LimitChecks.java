package com.memetrade.riskcontrol.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.PauseTrigger;
import com.memetrade.riskcontrol.api.RejectionReason;
import com.memetrade.riskcontrol.domain.SlippageEstimate.Estimate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The checks of the barrier (BR2.1 to BR2.12, BR3.1 to BR3.3), a pure function of {@link GateInputs}: no clock, no
 * database, no state. Every applicable check runs; the failed reasons come out in the fixed order of BR3.1 with one
 * detail each, and the pause triggers as a set.
 *
 * <p>Usable data (BR2.6, BR3.2): a price is usable when KNOWN, above zero and at most {@code freshnessMaxAge} old; a
 * liquidity when KNOWN, above zero and at most that old. A check that depends on an unusable value does not run and
 * never takes zero in its place: the reason of the missing value already refuses the order. Comparisons use the
 * rounded figures; the drawdown is compared in USDC against the floor, never through the rounded percentage.
 */
public final class LimitChecks {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SlippageEstimate policy;
    private final Duration freshnessMaxAge;

    public LimitChecks(SlippageEstimate policy, Duration freshnessMaxAge) {
        this.policy = policy;
        this.freshnessMaxAge = freshnessMaxAge;
    }

    public SlippageEstimate policy() {
        return policy;
    }

    /** F1 steps 5 to 7: the checks of an evaluation that is not a duplicate. */
    public CheckOutcome evaluate(GateInputs inputs) {
        Reading reading = read(inputs);
        BotLimitsVersionRef limits = inputs.limits();
        RiskSnapshot risk = inputs.risk();
        boolean buy = inputs.order().side() == OrderSide.BUY;
        List<ReasonDetail> failed = new ArrayList<>();
        Set<PauseTrigger> triggers = EnumSet.noneOf(PauseTrigger.class);

        Optional<BigDecimal> value = orderValue(inputs, reading);
        Optional<Estimate> estimate = estimate(inputs, reading);

        if (buy && value.isPresent()) {
            checkExposures(value.get(), limits, risk, failed);
        }
        estimate.ifPresent(e -> checkSlippage(e, limits, failed));
        checkLiquidity(inputs.liquidity(), limits, failed);
        reading.stale().ifPresent(failed::add);
        checkInterval(inputs.now(), limits, risk, failed);
        if (buy) {
            estimate.ifPresent(e -> checkBalance(e, risk, failed));
        }
        checkDailyLoss(limits, risk, failed, triggers);
        checkDrawdown(estimate, limits, risk, failed, triggers);

        failed.sort(Comparator.comparing(ReasonDetail::reason));
        return new CheckOutcome(
                failed,
                triggers,
                estimate.map(e -> RiskFigure.known(e.slippagePct())).orElse(RiskFigure.unknown()),
                value.map(RiskFigure::known).orElse(RiskFigure.unknown()));
    }

    /**
     * F2: the second evaluation of the same decision. No check runs on the data read in this call (they are only
     * recorded); the only reason is DUPLICATE, with the decision and the original evaluation (BR2.8).
     */
    public CheckOutcome duplicate(GateInputs inputs, UUID originalEvaluationId) {
        Reading reading = read(inputs);
        Optional<BigDecimal> value = orderValue(inputs, reading);
        Optional<Estimate> estimate = estimate(inputs, reading);
        ReasonDetail detail = new ReasonDetail(
                RejectionReason.DUPLICATE,
                inputs.order().decisionId().toString(),
                originalEvaluationId.toString(),
                DetailUnit.REFERENCE);
        return new CheckOutcome(
                List.of(detail),
                Set.of(),
                estimate.map(e -> RiskFigure.known(e.slippagePct())).orElse(RiskFigure.unknown()),
                value.map(RiskFigure::known).orElse(RiskFigure.unknown()));
    }

    /** BR1.4: value = money(quantity x price with 12 decimals); only with a usable price. */
    private static Optional<BigDecimal> orderValue(GateInputs inputs, Reading reading) {
        return reading.price()
                .map(price -> SlippageEstimate.money(inputs.order()
                        .requestedQuantity()
                        .multiply(price.setScale(SlippageEstimate.PRICE, RoundingMode.HALF_EVEN))));
    }

    /** BR1.5, BR1.6: only with a usable price AND a usable liquidity. */
    private Optional<Estimate> estimate(GateInputs inputs, Reading reading) {
        return reading.price().flatMap(price -> reading.liquidity()
                .map(liquidity ->
                        policy.estimate(inputs.order().side(), inputs.order().requestedQuantity(), price, liquidity)));
    }

    /** BR2.1 to BR2.3 (buy only, usable price). */
    private static void checkExposures(
            BigDecimal value, BotLimitsVersionRef limits, RiskSnapshot risk, List<ReasonDetail> failed) {
        if (value.compareTo(limits.maxOrderSize()) > 0) {
            failed.add(usdc(RejectionReason.ORDER_SIZE, value, limits.maxOrderSize()));
        }
        BigDecimal tokenAfter = SlippageEstimate.money(risk.tokenExposure().add(value));
        if (tokenAfter.compareTo(limits.maxTokenExposure()) > 0) {
            failed.add(usdc(RejectionReason.TOKEN_EXPOSURE, tokenAfter, limits.maxTokenExposure()));
        }
        BigDecimal totalAfter = SlippageEstimate.money(risk.totalExposure().add(value));
        if (totalAfter.compareTo(limits.maxTotalExposure()) > 0) {
            failed.add(usdc(RejectionReason.TOTAL_EXPOSURE, totalAfter, limits.maxTotalExposure()));
        }
    }

    /** BR2.4 (buy and sell, usable price and liquidity). */
    private static void checkSlippage(Estimate estimate, BotLimitsVersionRef limits, List<ReasonDetail> failed) {
        if (estimate.slippagePct().compareTo(limits.maxSlippagePct()) > 0) {
            failed.add(new ReasonDetail(
                    RejectionReason.SLIPPAGE,
                    estimate.slippagePct().toPlainString(),
                    text(limits.maxSlippagePct(), SlippageEstimate.PERCENT),
                    DetailUnit.PERCENT));
        }
    }

    /** BR2.5: UNKNOWN liquidity is refused with UNKNOWN measured; a KNOWN one is compared, even when stale. */
    private static void checkLiquidity(MarketValue liquidity, BotLimitsVersionRef limits, List<ReasonDetail> failed) {
        String limit = text(limits.minLiquidity(), SlippageEstimate.MONEY);
        if (!(liquidity instanceof MarketValue.Known known)) {
            failed.add(new ReasonDetail(RejectionReason.MIN_LIQUIDITY, ReasonDetail.UNKNOWN, limit, DetailUnit.USDC));
            return;
        }
        if (known.value().signum() <= 0 || known.value().compareTo(limits.minLiquidity()) < 0) {
            failed.add(new ReasonDetail(
                    RejectionReason.MIN_LIQUIDITY,
                    text(known.value(), SlippageEstimate.MONEY),
                    limit,
                    DetailUnit.USDC));
        }
    }

    /** BR2.7: now must be at least lastTradeAt + the interval; without a previous operation nothing restricts. */
    private static void checkInterval(
            Instant now, BotLimitsVersionRef limits, RiskSnapshot risk, List<ReasonDetail> failed) {
        risk.lastTradeAt().ifPresent(last -> {
            if (now.isBefore(last.plusSeconds(limits.minIntervalSeconds()))) {
                long since = Math.max(0, Duration.between(last, now).getSeconds());
                failed.add(new ReasonDetail(
                        RejectionReason.INTERVAL,
                        Long.toString(since),
                        Long.toString(limits.minIntervalSeconds()),
                        DetailUnit.SECONDS));
            }
        });
    }

    /** BR2.9 (buy only, usable price and liquidity). */
    private static void checkBalance(Estimate estimate, RiskSnapshot risk, List<ReasonDetail> failed) {
        if (estimate.buyCost().compareTo(risk.availableVirtualBalance()) > 0) {
            failed.add(usdc(
                    RejectionReason.INSUFFICIENT_PAPER_BALANCE, estimate.buyCost(), risk.availableVirtualBalance()));
        }
    }

    /** BR2.10: reaching the limit refuses and triggers the pause; the loss already includes the fees. */
    private static void checkDailyLoss(
            BotLimitsVersionRef limits, RiskSnapshot risk, List<ReasonDetail> failed, Set<PauseTrigger> triggers) {
        if (risk.realizedDayLossWithFees().compareTo(limits.maxDailyLoss()) >= 0) {
            failed.add(usdc(RejectionReason.DAILY_LOSS, risk.realizedDayLossWithFees(), limits.maxDailyLoss()));
            triggers.add(PauseTrigger.DAILY_LOSS);
        }
    }

    /**
     * BR2.11: empty equity refuses with UNKNOWN measured and no trigger (Q6); equity at or below the floor refuses and
     * triggers the pause; otherwise, with usable data, equity minus the estimated drop below the floor refuses without
     * trigger (Q7). The comparison is in USDC against the floor.
     */
    private static void checkDrawdown(
            Optional<Estimate> estimate,
            BotLimitsVersionRef limits,
            RiskSnapshot risk,
            List<ReasonDetail> failed,
            Set<PauseTrigger> triggers) {
        String limit = text(limits.maxDrawdownPct(), SlippageEstimate.PERCENT);
        if (risk.equity().isEmpty()) {
            failed.add(new ReasonDetail(RejectionReason.DRAWDOWN, ReasonDetail.UNKNOWN, limit, DetailUnit.PERCENT));
            return;
        }
        BigDecimal peak = risk.peakEquity();
        if (peak.signum() <= 0) {
            throw new IllegalStateException("The peak equity of C13 must be above zero");
        }
        BigDecimal equity = risk.equity().get();
        BigDecimal floor = SlippageEstimate.money(
                peak.multiply(BigDecimal.ONE.subtract(limits.maxDrawdownPct().divide(HUNDRED))));
        if (equity.compareTo(floor) <= 0) {
            failed.add(
                    new ReasonDetail(RejectionReason.DRAWDOWN, drawdownPct(peak, equity), limit, DetailUnit.PERCENT));
            triggers.add(PauseTrigger.DRAWDOWN);
            return;
        }
        if (estimate.isPresent()) {
            BigDecimal after = equity.subtract(estimate.get().equityDrop());
            if (after.compareTo(floor) < 0) {
                failed.add(new ReasonDetail(
                        RejectionReason.DRAWDOWN, drawdownPct(peak, after), limit, DetailUnit.PERCENT));
            }
        }
    }

    /** percent((peak - equity) / peak x 100), shown in the detail only. */
    private static String drawdownPct(BigDecimal peak, BigDecimal equity) {
        return peak.subtract(equity)
                .multiply(HUNDRED)
                .divide(peak, SlippageEstimate.PERCENT, RoundingMode.HALF_EVEN)
                .toPlainString();
    }

    /**
     * BR2.6: which of price and liquidity are usable now, and the single STALE_DATA detail (the price one when the
     * price failed, otherwise the liquidity one). A negative age (observed in the future) counts as 0 s.
     */
    private Reading read(GateInputs inputs) {
        Optional<BigDecimal> price = Optional.empty();
        Optional<ReasonDetail> stale = Optional.empty();
        String limit = Long.toString(freshnessMaxAge.getSeconds());
        if (inputs.referencePrice() instanceof MarketValue.Known known
                && known.value().signum() > 0) {
            long age = ageSeconds(known.observedAt(), inputs.now());
            if (age > freshnessMaxAge.getSeconds()) {
                stale = Optional.of(staleDetail(Long.toString(age), limit));
            } else {
                price = Optional.of(known.value());
            }
        } else {
            stale = Optional.of(staleDetail(ReasonDetail.UNKNOWN, limit));
        }
        Optional<BigDecimal> liquidity = Optional.empty();
        if (inputs.liquidity() instanceof MarketValue.Known known
                && known.value().signum() > 0) {
            long age = ageSeconds(known.observedAt(), inputs.now());
            if (age > freshnessMaxAge.getSeconds()) {
                if (stale.isEmpty()) {
                    stale = Optional.of(staleDetail(Long.toString(age), limit));
                }
            } else {
                liquidity = Optional.of(known.value());
            }
        }
        return new Reading(price, liquidity, stale);
    }

    private static long ageSeconds(Instant observedAt, Instant now) {
        return Math.max(0, Duration.between(observedAt, now).getSeconds());
    }

    private static ReasonDetail staleDetail(String measured, String limit) {
        return new ReasonDetail(RejectionReason.STALE_DATA, measured, limit, DetailUnit.SECONDS);
    }

    private static ReasonDetail usdc(RejectionReason reason, BigDecimal measured, BigDecimal limit) {
        return new ReasonDetail(
                reason, text(measured, SlippageEstimate.MONEY), text(limit, SlippageEstimate.MONEY), DetailUnit.USDC);
    }

    /** The value in the scale of its unit when it fits without rounding, otherwise exactly as received. */
    static String text(BigDecimal value, int scale) {
        return value.scale() <= scale ? value.setScale(scale).toPlainString() : value.toPlainString();
    }

    /** The usable price and liquidity (empty when not usable) and the single STALE_DATA detail. */
    private record Reading(Optional<BigDecimal> price, Optional<BigDecimal> liquidity, Optional<ReasonDetail> stale) {}
}
