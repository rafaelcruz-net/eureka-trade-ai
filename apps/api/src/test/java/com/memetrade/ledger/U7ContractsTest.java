package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.ledger.api.PaperEnums;
import com.memetrade.ledger.api.PaperExecution.PaperOrderRequest;
import com.memetrade.tenancy.RouteRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 3: the additions of U7 to the contracts and to the code of U1 (BR1.10, NFR6.8). */
@Tag("u7")
class U7ContractsTest {

    @Test
    void theIdempotentOperationsOfU7UseTheDefaultRetention() {
        assertThat(IdempotentOperation.PAPER_FUND.operationName()).isEqualTo("paper.fund");
        assertThat(IdempotentOperation.PAPER_CLOSE_POSITIONS.operationName()).isEqualTo("paper.close-positions");
        assertThat(IdempotentOperation.RECONCILIATION_RUN.operationName()).isEqualTo("pnl.reconciliation.run");
        assertThat(IdempotentOperation.PAPER_FUND.retention()).isEqualTo(IdempotentOperation.Retention.DEFAULT);
        assertThat(IdempotentOperation.PAPER_CLOSE_POSITIONS.retention())
                .isEqualTo(IdempotentOperation.Retention.DEFAULT);
    }

    @Test
    void theRoutesOfC5AreClientRoutesWithANegativeTestAndTheJobsAreGlobal() throws Exception {
        RouteRegistry registry = RouteRegistry.load();

        assertThat(registry.routes())
                // C5 is PAPER: the REAL P&L of 261113-trading-real-bot U4 (/api/v1/real/pnl) is checked by
                // RouteRegistryTest.
                .filteredOn(entry -> !entry.key().contains("/api/v1/real/")
                        && (entry.key().contains("/trades")
                                || entry.key().contains("/day-usage")
                                || entry.key().contains("/ledger")
                                || entry.key().contains("/pnl")
                                || entry.key().contains("/reconciliation")))
                .extracting(RouteRegistry.Entry::key, RouteRegistry.Entry::scope)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("GET /api/v1/bots/{botId}/trades", "CLIENT"),
                        Tuple.tuple("GET /api/v1/bots/{botId}/day-usage", "CLIENT"),
                        Tuple.tuple("GET /api/v1/ledger", "CLIENT"),
                        Tuple.tuple("GET /api/v1/pnl", "CLIENT"),
                        // 261121-paper-real-unico U1 (C4): the P&L by mode, also a CLIENT route.
                        Tuple.tuple("GET /api/v1/pnl/by-mode", "CLIENT"),
                        // 261121-paper-real-unico U3 (C5, C6; BR8.1, BR8.2, BR5.3): the lines of a REAL bot and the
                        // comparison, both CLIENT routes; another tenant's bot is 404 without any data.
                        Tuple.tuple("GET /api/v1/bots/{botId}/pnl/lines", "CLIENT"),
                        Tuple.tuple("GET /api/v1/pnl/compare", "CLIENT"),
                        Tuple.tuple("GET /api/v1/reconciliation", "CLIENT"),
                        Tuple.tuple("POST /api/v1/reconciliation/run", "CLIENT"));
        // 261121-paper-real-unico U3: each new route names its cross-tenant negative test (RouteRegistryTest checks
        // that the named test exists).
        assertThat(registry.routes())
                .filteredOn(entry -> entry.key().equals("GET /api/v1/bots/{botId}/pnl/lines")
                        || entry.key().equals("GET /api/v1/pnl/compare"))
                .extracting(RouteRegistry.Entry::key, RouteRegistry.Entry::negativeTest)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(
                                "GET /api/v1/bots/{botId}/pnl/lines",
                                "com.memetrade.pnl.BotPnlLinesTenantIT#theLinesOfAnotherTenantAreNeverFoundNorRead"),
                        Tuple.tuple(
                                "GET /api/v1/pnl/compare",
                                "com.memetrade.pnl.PnlCompareTenantIT#aBotOfAnotherTenantWithAnotherStrategyIs404NeverA422"));
        assertThat(registry.jobs())
                .filteredOn(entry -> entry.key().equals("PendingCloseJob")
                        || entry.key().equals("EquityMarkingJob")
                        || entry.key().equals("ReconciliationJob")
                        || entry.key().equals("ReconciliationRunLimiterEvictionJob"))
                .extracting(RouteRegistry.Entry::scope)
                .containsExactly("GLOBAL", "GLOBAL", "GLOBAL", "GLOBAL");
        assertThat(registry.tenantFromClientOutsideAdmin()).isEmpty();
    }

    @Test
    void theAuditActionsOfU7AreUpperCase() {
        for (String action : new String[] {
            AuditActions.PAPER_ACCOUNT_FUNDED,
            AuditActions.PAPER_ORDER_FILLED,
            AuditActions.PAPER_ORDER_NOT_FILLED,
            AuditActions.POSITION_CLOSE_PENDING,
            AuditActions.POSITION_CLOSE_COMPLETED,
            AuditActions.LEDGER_ENTRY_COMPENSATED
        }) {
            assertThat(action).matches("[A-Z_]+");
        }
    }

    @Test
    void theOnlyModeIsPaperAndTheNotFilledReasonsAreTheSevenOfC15() {
        assertThat(PaperEnums.MODE).isEqualTo("PAPER");
        assertThat(PaperEnums.NotFilledReason.values())
                .extracting(Enum::name)
                .containsExactly(
                        "PRICE_UNAVAILABLE",
                        "NO_LIQUIDITY",
                        "QUANTITY_BELOW_MINIMUM",
                        "NO_POSITION",
                        "ACCOUNT_CLOSING",
                        "INSUFFICIENT_BALANCE",
                        "TENANT_MISMATCH");
        assertThat(PaperEnums.EntryType.values()).extracting(Enum::name).contains("FUNDING", "COMPENSATION");
    }

    @Test
    void anOrderRequestNeedsAPositiveQuantityAndADecision() {
        assertThatThrownBy(() -> new PaperOrderRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PaperEnums.OrderSide.BUY,
                        "solana",
                        "m",
                        BigDecimal.ZERO,
                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaperOrderRequest(
                        UUID.randomUUID(), null, PaperEnums.OrderSide.BUY, "solana", "m", BigDecimal.ONE, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theContractSummaryCarriesTheU7Additions() throws Exception {
        String summary = Files.readString(
                Path.of(
                        "..",
                        "..",
                        "aidlc",
                        "spaces",
                        "default",
                        "intents",
                        "261026-memetrade-saas-mvp",
                        "inception",
                        "contract-design",
                        "contract-summary.md"),
                StandardCharsets.UTF_8);

        assertThat(summary)
                .contains("MarketValue decimals(TokenId token);")
                .contains("SUMMARY_MISMATCH")
                .contains("POSITION_MISMATCH")
                .contains("/api/v1/reconciliation/run")
                .contains("CloseResult closePositions(UUID botId, long limitsVersion, String idempotencyKey)")
                .contains("RiskSnapshot snapshot(UUID botId, String chain, String tokenMint, LocalDate utcDay)")
                .contains("actions-added-by-u7")
                .contains("12 casas, `HALF_EVEN`");
    }
}
