package com.memetrade.riskcontrol.real.domain;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Inputs of the pure policy for the scenarios of functional-spec.md: a released wallet with the REAL on, a cap of
 * 10 SOL, 20 SOL free, a real source, an assessed token with fresh signals, no position and the photo of the day equal
 * to the current value. Each test changes only what its scenario says.
 */
final class PolicyFixture {

    static final long SOL = 1_000_000_000L;
    static final Instant NOW = Instant.parse("2026-11-14T15:00:00Z");
    static final SourceRef SOURCE = new SourceRef("coingecko", "CoinGecko", false);

    final UUID tenantId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID botId = UUID.randomUUID();
    final UUID botWalletId = UUID.randomUUID();
    final String mint = SyntheticIds.mint();

    boolean hasWallet = true;
    boolean realEnabled = true;
    boolean released = true;
    long capLamports = 10 * SOL;
    long feeReserveLamports = 30_000_000L;
    Set<RealPauseReason> pauses = EnumSet.noneOf(RealPauseReason.class);
    SourceKind source = SourceKind.REAL;
    AssessmentResult assessment = new AssessmentResult.Available(30, RiskBand.LOW, 1);
    boolean blocked;
    MarketValue price = known("0.0001", 10);
    MarketValue liquidity = known("500000", 10);
    MarketValue volume = known("250000", 10);
    final List<PricedPosition> positions = new ArrayList<>();
    long totalLamports = 20 * SOL;
    long orderReservedLamports;
    long withdrawalReservedLamports;
    long openGateReservedLamports;
    int approvedBuysToday;
    Optional<Long> snapshotStartValue = Optional.of(20 * SOL);
    long snapshotStartCap = 10 * SOL;
    RealFlows flows = RealFlows.NONE;
    RealRiskLimits limits = RealRiskLimits.DEFAULTS;

    static MarketValue known(String value, long secondsAgo) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, NOW.minusSeconds(secondsAgo), false);
    }

    static MarketValue unknown() {
        return new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE));
    }

    static LamportPrice lamports(String perBaseUnit, long secondsAgo) {
        return new LamportPrice.Known(new BigDecimal(perBaseUnit), NOW.minusSeconds(secondsAgo));
    }

    PolicyFixture position(String positionMint, long units, long cost, LamportPrice price) {
        positions.add(new PricedPosition(positionMint, units, cost, price));
        return this;
    }

    /** Sets the current value of the wallet through its balance (no position): total and free. */
    PolicyFixture balance(long total) {
        this.totalLamports = total;
        return this;
    }

    OrderIntent buy(long inputLamports) {
        return OrderIntent.botBuy(tenantId, userId, botId, UUID.randomUUID(), mint, inputLamports);
    }

    OrderIntent botSell() {
        return OrderIntent.botSell(tenantId, userId, botId, UUID.randomUUID(), mint, 1_000);
    }

    OrderIntent liquidation() {
        return OrderIntent.userLiquidation(tenantId, userId, mint, 1_000);
    }

    Optional<RealStateView> wallet() {
        if (!hasWallet) {
            return Optional.empty();
        }
        Map<RealPauseReason, Instant> since = new EnumMap<>(RealPauseReason.class);
        pauses.forEach(reason -> since.put(reason, NOW.minus(Duration.ofHours(1))));
        return Optional.of(new RealStateView(
                botWalletId,
                realEnabled,
                released,
                RealOperationMode.AUTO_FULL,
                capLamports,
                feeReserveLamports,
                pauses,
                since));
    }

    RealBalance ledgerBalance() {
        long reserved = orderReservedLamports + withdrawalReservedLamports;
        return new RealBalance(
                totalLamports,
                reserved,
                totalLamports - reserved,
                Set.of(),
                !positions.isEmpty(),
                orderReservedLamports,
                withdrawalReservedLamports);
    }

    Optional<DaySnapshot> snapshot() {
        LocalDate day = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
        return snapshotStartValue.map(start -> new DaySnapshot(
                tenantId,
                botWalletId,
                day,
                start,
                snapshotStartCap,
                day.atStartOfDay().toInstant(ZoneOffset.UTC),
                DaySnapshot.TakenBy.DAY_JOB));
    }

    RealGateInputs inputs(OrderIntent intent) {
        Optional<BuyInputs> buy = intent.isBuy()
                ? Optional.of(new BuyInputs(
                        assessment,
                        blocked,
                        price,
                        liquidity,
                        volume,
                        List.copyOf(positions),
                        ledgerBalance(),
                        openGateReservedLamports,
                        approvedBuysToday,
                        snapshot(),
                        flows))
                : Optional.empty();
        return new RealGateInputs(NOW, limits, intent, wallet(), source, buy);
    }
}
