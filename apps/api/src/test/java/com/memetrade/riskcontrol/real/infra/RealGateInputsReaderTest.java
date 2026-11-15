package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealGateUnavailable.Dependency;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Step 6 of the U3, written before the code (U3-NFR4.1): the reader fails CLOSED. A technical failure of M1, M2 or M4
 * becomes {@link RealGateUnavailable} with its dependency (never an approval); a failure of the indicator becomes an
 * unavailable assessment, which refuses the buy with NO_ASSESSMENT.
 */
class RealGateInputsReaderTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final RealStateQuery state = mock(RealStateQuery.class);
    private final RealLedger ledger = mock(RealLedger.class);
    private final MarketDataQuery market = mock(MarketDataQuery.class);
    private final RiskAssessmentQuery assessment = mock(RiskAssessmentQuery.class);
    private final JdbcRealRiskStore store = mock(JdbcRealRiskStore.class);
    private final RealGateInputsReader reader = new RealGateInputsReader(state, ledger, market, assessment, store);

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final RealStateView wallet =
            new RealStateView(UUID.randomUUID(), true, true, RealOperationMode.AUTO_FULL, 10, 0, Set.of(), Map.of());
    private final OrderIntent buy =
            OrderIntent.botBuy(tenant, user, UUID.randomUUID(), UUID.randomUUID(), SyntheticIds.mint(), 1);

    private void healthy() {
        when(ledger.view(any(), any(), any()))
                .thenReturn(new com.memetrade.ledger.api.RealLedgerView(
                        new RealBalance(0, 0, 0, Set.of(), false), List.of(), com.memetrade.ledger.api.RealFlows.NONE));
        MarketValue unknown = new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.empty());
        when(market.referencePrices(any())).thenReturn(Map.of());
        when(market.decimalsOf(any())).thenReturn(Map.of());
        when(market.liquidity(any())).thenReturn(unknown);
        when(market.volume24h(any())).thenReturn(unknown);
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void aFailureOfTheWalletIsUnavailableWallet() {
        when(state.readRealState(tenant, user)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> reader.wallet(tenant, user))
                .isInstanceOfSatisfying(RealGateUnavailable.class, e -> assertThat(e.dependency())
                        .isEqualTo(Dependency.WALLET));
    }

    @Test
    void aFailureOfTheSourceKindIsUnavailableMarket() {
        when(market.activeSourceKind()).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(reader::source)
                .isInstanceOfSatisfying(RealGateUnavailable.class, e -> assertThat(e.dependency())
                        .isEqualTo(Dependency.MARKET));
    }

    @Test
    void aFailureOfTheLedgerIsUnavailableLedger() {
        healthy();
        when(ledger.view(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> reader.buy(buy, wallet, NOW))
                .isInstanceOfSatisfying(RealGateUnavailable.class, e -> assertThat(e.dependency())
                        .isEqualTo(Dependency.LEDGER));
    }

    @Test
    void aFailureOfThePricesIsUnavailableMarket() {
        healthy();
        when(market.referencePrices(any())).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> reader.buy(buy, wallet, NOW))
                .isInstanceOfSatisfying(RealGateUnavailable.class, e -> assertThat(e.dependency())
                        .isEqualTo(Dependency.MARKET));
    }

    @Test
    void aFailureOfTheIndicatorIsAnUnavailableAssessmentNeverAnApproval() {
        healthy();
        when(assessment.assess(any())).thenThrow(new IllegalStateException("down"));

        assertThat(reader.buy(buy, wallet, NOW).assessment()).isInstanceOf(AssessmentResult.Unavailable.class);
    }

    @Test
    void aTokenTheMarketDoesNotAnswerIsUnknown() {
        healthy();

        assertThat(reader.buy(buy, wallet, NOW).price().isKnown()).isFalse();
    }

    /**
     * Review R-01: the buy reads balance, positions and the flows after the photo in ONE read of the REAL ledger (one
     * SQL statement, one snapshot of PostgreSQL), never in separate reads a fill or a deposit could land between.
     */
    @Test
    void theBuyReadsTheLedgerOnceWithTheInstantOfThePhoto() {
        healthy();
        UUID walletId = wallet.botWalletId();
        Instant photo = NOW.minusSeconds(3600);
        when(store.snapshot(any(), any(), any()))
                .thenReturn(Optional.of(new com.memetrade.riskcontrol.real.domain.DaySnapshot(
                        tenant,
                        walletId,
                        java.time.LocalDate.of(2026, 11, 14),
                        1,
                        1,
                        photo,
                        com.memetrade.riskcontrol.real.domain.DaySnapshot.TakenBy.DAY_JOB)));

        reader.buy(buy, wallet, NOW);

        org.mockito.Mockito.verify(ledger).view(tenant, walletId, Optional.of(photo));
        org.mockito.Mockito.verifyNoMoreInteractions(ledger);
    }

    /** Review R-01: the jobs read the value and the flows the same way, in one read. */
    @Test
    void theValueOfAJobIsOneReadToo() {
        healthy();
        UUID walletId = wallet.botWalletId();
        Instant photo = NOW.minusSeconds(60);

        RealGateInputsReader.WalletValue value = reader.value(tenant, walletId, Optional.of(photo));

        assertThat(value.value()).isZero();
        assertThat(value.flows()).isEqualTo(com.memetrade.ledger.api.RealFlows.NONE);
        org.mockito.Mockito.verify(ledger).view(tenant, walletId, Optional.of(photo));
        org.mockito.Mockito.verifyNoMoreInteractions(ledger);
    }

    /** Review R-02: only a failure without a SQL error leaves the transaction of the caller usable. */
    @Test
    void theFailureSaysWhetherTheTransactionOfTheCallerIsStillUsable() {
        assertThat(new RealGateUnavailable(Dependency.LOCK).transactionUsable()).isTrue();
        assertThat(new RealGateUnavailable(Dependency.MARKET, new IllegalStateException("down")).transactionUsable())
                .isTrue();
        assertThat(new RealGateUnavailable(Dependency.LEDGER, new DataAccessResourceFailureException("down"))
                        .transactionUsable())
                .isFalse();
        assertThat(new RealGateUnavailable(
                                Dependency.WALLET,
                                new IllegalStateException("wrapped", new java.sql.SQLException("aborted", "25P02")))
                        .transactionUsable())
                .isFalse();
    }
}
