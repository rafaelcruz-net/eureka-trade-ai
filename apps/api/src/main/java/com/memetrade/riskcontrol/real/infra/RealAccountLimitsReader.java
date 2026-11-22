package com.memetrade.riskcontrol.real.infra;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import com.memetrade.riskcontrol.api.RealAccountLimitsView;
import com.memetrade.riskcontrol.api.RealAccountLimitsView.Amount;
import com.memetrade.riskcontrol.api.RealLimitsUnavailable;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1, C9 (BR3.1, BR3.2, ADR-008): the REAL limits of the account from the same inputs as the
 * {@code RealRiskPolicy}: the limits of {@code memetrade.real.risk.*} ({@link RealRiskLimits}), the cap of the ACTIVE
 * bot wallet (M1) and the photo of the UTC day ({@code real_risk_day}) for the base of the daily loss. Exact integer
 * arithmetic with overflow checks; a cap of zero is NOT_DEFINED, never a zero limit. Exists in the api (route C3) and in
 * the worker (the REAL branch of the cycle); no network call, no lock, no write.
 */
@Component
public class RealAccountLimitsReader implements RealAccountLimits {

    static final long BPS = 10_000;

    private final RealStateQuery states;
    private final JdbcRealRiskStore store;
    private final RealRiskLimits limits;
    private final Clock clock;

    public RealAccountLimitsReader(RealStateQuery states, JdbcRealRiskStore store, RealRiskLimits limits, Clock clock) {
        this.states = states;
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public Optional<RealAccountLimitsView> read(UUID tenantId, UUID userId) {
        try {
            Optional<RealStateView> wallet = states.readRealState(tenantId, userId);
            if (wallet.isEmpty()) {
                return Optional.empty();
            }
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            Optional<Long> startCap =
                    store.snapshot(tenantId, wallet.get().botWalletId(), today).map(DaySnapshot::startCapLamports);
            return Optional.of(of(wallet.get().capLamports(), startCap, limits));
        } catch (DataAccessException | ArithmeticException failure) {
            // Review R-03: an overflow of the exact share fails closed, like a failed read (503 in C3, no buy in the
            // cycle), never a wrapped value nor a 500.
            throw new RealLimitsUnavailable(failure);
        }
    }

    /**
     * BR3.1, BR3.2: the four limits of a cap. {@code startCap} is the cap of the photo of the UTC day, absent before the
     * first photo (the base is then the cap now, as in the barrier).
     */
    static RealAccountLimitsView of(long capLamports, Optional<Long> startCap, RealRiskLimits limits) {
        if (capLamports <= 0) {
            return new RealAccountLimitsView(
                    new Amount.NotDefined(), new Amount.NotDefined(), limits.dailyBuysMax(), new Amount.NotDefined());
        }
        long base = Math.min(startCap.orElse(capLamports), capLamports);
        return new RealAccountLimitsView(
                new Amount.Known(capLamports, null),
                new Amount.Known(share(capLamports, limits.orderMaxBps()), percent(limits.orderMaxBps())),
                limits.dailyBuysMax(),
                new Amount.Known(share(base, limits.dailyLossMaxBps()), percent(limits.dailyLossMaxBps())));
    }

    /** {@code floor(lamports x bps / 10000)}, exact. */
    static long share(long lamports, int bps) {
        return Math.multiplyExact(lamports, bps) / BPS;
    }

    /** {@code bps / 100}, exact, without trailing zeros (1000 is 10, 1250 is 12.5). */
    static BigDecimal percent(int bps) {
        BigDecimal value = BigDecimal.valueOf(bps).movePointLeft(2).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0) : value;
    }
}
