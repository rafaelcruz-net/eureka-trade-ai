package com.memetrade.pnl.application;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.AccountView;
import com.memetrade.ledger.api.LedgerReadModel.PositionView;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.pnl.domain.EquityCalculator;
import com.memetrade.pnl.domain.RiskSnapshotCalculator;
import com.memetrade.pnl.domain.RiskSnapshotCalculator.DayFill;
import com.memetrade.pnl.domain.RiskSnapshotCalculator.Exposure;
import com.memetrade.pnl.infra.JdbcEquityPeakStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C13 for U6 (F6), inside the cycle transaction ({@code MANDATORY}). Tenant and person come from the authentication
 * context of the cycle; the day loss is read straight from the fills and FEE entries of the UTC day (never from the
 * daily summary, so a wrong summary never changes what the barrier sees); the equity is empty when a price is not
 * reliable (BR7.4).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = PortfolioRiskView.TenantMismatchException.class)
public class PortfolioRiskViewImpl implements PortfolioRiskView {

    private final LedgerReadModel ledger;
    private final PortfolioMarks marks;
    private final JdbcEquityPeakStore peaks;
    private final AuthContext auth;
    private final PnlAudit audit;

    public PortfolioRiskViewImpl(
            LedgerReadModel ledger, PortfolioMarks marks, JdbcEquityPeakStore peaks, AuthContext auth, PnlAudit audit) {
        this.ledger = ledger;
        this.marks = marks;
        this.peaks = peaks;
        this.auth = auth;
        this.audit = audit;
    }

    @Override
    public RiskSnapshot snapshot(UUID botId, String chain, String tokenMint, LocalDate utcDay) {
        Principal person = auth.current();
        AccountView account =
                ledger.account(botId).orElseThrow(() -> new IllegalStateException("No PAPER account for bot " + botId));
        if (!account.tenantId().equals(person.tenantId())
                || !account.ownerUserId().equals(person.userId())) {
            audit.tenantMismatch(person, botId, "pnl.risk-snapshot");
            throw new TenantMismatchException(botId);
        }
        List<PositionView> positions = ledger.positions(botId).stream()
                .filter(p -> p.quantity().signum() > 0)
                .toList();
        Instant dayStart = utcDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<DayFill> dayFills = ledger
                .fills(
                        person.tenantId(),
                        person.userId(),
                        Optional.of(botId),
                        dayStart,
                        dayStart.plus(java.time.Duration.ofDays(1)))
                .stream()
                .map(fill -> new DayFill(fill.realized(), fill.fees()))
                .toList();
        BigDecimal balance = ledger.balance(botId);
        Optional<BigDecimal> equity = EquityCalculator.equity(balance, marks.openMarks(positions));
        BigDecimal peak = peaks.find(botId)
                .map(JdbcEquityPeakStore.Peak::peakEquity)
                .orElseThrow(() -> new IllegalStateException("No equity peak for bot " + botId));
        return RiskSnapshotCalculator.snapshot(
                chain,
                tokenMint,
                positions.stream()
                        .map(p -> new Exposure(p.chain(), p.tokenMint(), p.costBasis()))
                        .toList(),
                dayFills,
                equity,
                peak,
                balance,
                ledger.lastFilledAt(botId));
    }
}
