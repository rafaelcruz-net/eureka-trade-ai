package com.memetrade.privacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionTerm;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 8 of u2-retention (US1.4, BR2.9; tests written after this layer): the retention catalog of the Privacy module
 * holds the rows of the policy (13 of v2, 22 of v3 since 261113-trading-real-bot U6) in its order, each with who applies it, and refuses an inconsistent row.
 */
@Tag("u2-retention")
class RetentionCatalogTest {

    @Test
    void theCatalogHasExactlyTheTwentyTwoRowsOfThePolicyInTheirOrder() {
        List<RetentionRule> rules = RetentionCatalog.rules();

        assertThat(rules).hasSize(22);
        assertThat(rules)
                .extracting(RetentionRule::id)
                .containsExactly(
                        "ACCOUNT",
                        "PAPER_HISTORY",
                        "TERMS_ACCEPTANCES",
                        "AUDIT_TRAIL",
                        "AUDIT_OUTBOX",
                        "FEEDBACK",
                        "HOLD_NO_TRADE_DECISIONS",
                        "APPROVED_RISK_EVALUATIONS",
                        "IDEMPOTENCY_KEYS",
                        "LOGIN_CHALLENGES",
                        "SESSIONS",
                        "DATA_SUBJECT_REQUESTS",
                        // 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7).
                        "REAL_RISK_EVALUATIONS",
                        // 261113-trading-real-bot U6, Step 19 (review-03 R-12): the data of the REAL in the policy v3.
                        "REAL_BOT_WALLET",
                        "REAL_LEDGER",
                        "REAL_ORDERS",
                        "REAL_DECISION_REQUESTS",
                        "REAL_CONDITIONAL_EXITS",
                        "REAL_RELEASES",
                        "REAL_RECONCILIATIONS",
                        "REAL_DIVERGENCES",
                        "REAL_TXSIGNER_COMMANDS");
        assertThat(rules)
                .extracting(RetentionRule::order)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 22).boxed().toList());
    }

    @Test
    void theRowsWhileTheAccountExistsAreGuardsAndEveryOtherRowHasItsPurge() {
        List<RetentionRule> rules = RetentionCatalog.rules();

        assertThat(rules.stream().filter(rule -> rule.enforcer().kind() == Enforcer.Kind.GUARD))
                .extracting(RetentionRule::id)
                .containsExactly("ACCOUNT", "PAPER_HISTORY", "TERMS_ACCEPTANCES", "REAL_BOT_WALLET", "REAL_LEDGER");
        // u4-data-requests (BR4.15): the data subject requests are no longer PLANNED; their purge exists.
        assertThat(rules.stream().filter(rule -> rule.enforcer().kind() == Enforcer.Kind.PLANNED))
                .isEmpty();
        assertThat(rules.stream().filter(rule -> rule.enforcer().kind() == Enforcer.Kind.PURGE))
                .extracting(rule -> rule.enforcer().purgeId().orElseThrow())
                .containsExactly(
                        "auditRetention",
                        "auditOutboxCleanup",
                        "feedbackPurge",
                        "decisionPurge",
                        "riskcontrol-purge",
                        "idempotencyCleanup",
                        "loginChallengePurge",
                        "sessionPurge",
                        "dataSubjectRequestPurge",
                        "real-risk-purge",
                        "real-execution-purge/orders",
                        "real-execution-purge/decisions",
                        "real-exits-purge",
                        "real-ops-purge/releases",
                        "real-ops-purge/reconciliations",
                        "real-ops-purge/divergences",
                        "real-ops-purge/txsigner-commands");
    }

    /**
     * 261113-trading-real-bot U6, Step 19 (review-03 R-12), written before the rows: the data of the REAL that the policy
     * v3 lists has its own row, with the period the code applies. The bot wallet and the ledger are never purged: they
     * stay while the account exists (the ledger is append-only, deposits too) and the anonymization closes the wallet
     * and clears its addresses and consents. Orders, conditional exits, releases and divergences go 5 years after their
     * anchor (the triggers of 0019, 0021 and 0022); decision requests, reconciliations and commands to the TxSigner go
     * after 13 months.
     */
    @Test
    void theRealRowsStateWhatTheCodeKeepsAndWhatItsPurgesDelete() {
        assertGuard("REAL_BOT_WALLET", "Carteira do bot", "Anonimizar");
        assertGuard("REAL_LEDGER", "Ledger", "nunca é apagado nem editado");
        assertPurge("REAL_ORDERS", RetentionTerm.months(60), RetentionAnchor.CREATED, "real-execution-purge/orders");
        assertPurge(
                "REAL_DECISION_REQUESTS",
                RetentionTerm.months(13),
                RetentionAnchor.CONCLUDED,
                "real-execution-purge/decisions");
        assertPurge("REAL_CONDITIONAL_EXITS", RetentionTerm.months(60), RetentionAnchor.ENDED, "real-exits-purge");
        assertPurge("REAL_RELEASES", RetentionTerm.months(60), RetentionAnchor.ENDED, "real-ops-purge/releases");
        assertPurge(
                "REAL_RECONCILIATIONS",
                RetentionTerm.months(13),
                RetentionAnchor.CREATED,
                "real-ops-purge/reconciliations");
        assertPurge(
                "REAL_DIVERGENCES", RetentionTerm.months(60), RetentionAnchor.CONCLUDED, "real-ops-purge/divergences");
        assertPurge(
                "REAL_TXSIGNER_COMMANDS",
                RetentionTerm.months(13),
                RetentionAnchor.CONCLUDED,
                "real-ops-purge/txsigner-commands");
        assertThat(RetentionCatalog.find("REAL_ORDERS").orElseThrow().toItem().period())
                .isEqualTo("5 anos");
        assertThat(RetentionCatalog.find("REAL_CONDITIONAL_EXITS")
                        .orElseThrow()
                        .toItem()
                        .period())
                .isEqualTo("5 anos após o fim");
    }

    private static void assertGuard(String id, String dataItemPart, String endActionPart) {
        RetentionRule rule = RetentionCatalog.find(id).orElseThrow();
        assertThat(rule.enforcer().kind()).as(id).isEqualTo(Enforcer.Kind.GUARD);
        assertThat(rule.period().kind()).as(id).isEqualTo(RetentionPeriod.Kind.WHILE_ACCOUNT_EXISTS);
        assertThat(rule.dataItem()).as(id).contains(dataItemPart);
        assertThat(rule.endAction()).as(id).contains(endActionPart);
    }

    private static void assertPurge(String id, RetentionTerm term, RetentionAnchor anchor, String purgeId) {
        RetentionRule rule = RetentionCatalog.find(id).orElseThrow();
        assertThat(rule.enforcer().purgeId()).as(id).contains(purgeId);
        assertThat(rule.period().term()).as(id).contains(term);
        assertThat(rule.period().anchor()).as(id).contains(anchor);
        assertThat(rule.endAction()).as(id).isEqualTo("Apagar");
        assertThat(rule.dataItem()).as(id).contains("REAL");
    }

    /**
     * 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7, tests written before the row): the evaluations,
     * the closed reservations and the daily photos of the REAL barrier stay 13 months from when they are written and are
     * then deleted by the daily purge of the REAL barrier ({@code real-risk-purge}), the same end the purge applies.
     */
    @Test
    void theRealBarrierEvaluationsStayThirteenMonthsAndAreDeletedByTheRealPurge() {
        RetentionRule real = RetentionCatalog.find("REAL_RISK_EVALUATIONS").orElseThrow();

        assertThat(real.period().kind()).isEqualTo(RetentionPeriod.Kind.FIXED);
        assertThat(real.period().term()).contains(RetentionTerm.months(13));
        assertThat(real.period().anchor()).contains(RetentionAnchor.CREATED);
        assertThat(real.period().describe()).isEqualTo("13 months from CREATED");
        assertThat(real.enforcer().kind()).isEqualTo(Enforcer.Kind.PURGE);
        assertThat(real.enforcer().purgeId()).contains("real-risk-purge");
        assertThat(real.endAction()).isEqualTo("Apagar");
        assertThat(real.toItem().period()).isEqualTo("13 meses");
    }

    @Test
    void theRealRowIsNotThePaperRowAndKeepsItsOwnPeriod() {
        RetentionRule paper = RetentionCatalog.find("APPROVED_RISK_EVALUATIONS").orElseThrow();
        RetentionRule real = RetentionCatalog.find("REAL_RISK_EVALUATIONS").orElseThrow();

        assertThat(real.dataItem()).isNotEqualTo(paper.dataItem()).contains("REAL");
        assertThat(real.enforcer().purgeId()).isNotEqualTo(paper.enforcer().purgeId());
        assertThat(paper.period().term()).contains(RetentionTerm.days(30));
    }

    @Test
    void theDataSubjectRequestsLeaveTwelveMonthsAfterTheirConclusion() {
        RetentionRule requests = RetentionCatalog.find("DATA_SUBJECT_REQUESTS").orElseThrow();

        assertThat(requests.period().term()).contains(RetentionTerm.months(12));
        assertThat(requests.period().anchor()).contains(RetentionAnchor.CONCLUDED);
        assertThat(requests.enforcer().purgeId()).contains("dataSubjectRequestPurge");
    }

    @Test
    void theNewPurgesOfThisUnitStateTheirPeriodsAndAnchors() {
        RetentionRule challenges = RetentionCatalog.find("LOGIN_CHALLENGES").orElseThrow();
        RetentionRule sessions = RetentionCatalog.find("SESSIONS").orElseThrow();

        assertThat(challenges.period().term()).contains(RetentionTerm.hours(24));
        assertThat(challenges.period().anchor()).contains(RetentionAnchor.EXPIRED);
        assertThat(sessions.period().term()).contains(RetentionTerm.days(30));
        assertThat(sessions.period().anchor()).contains(RetentionAnchor.ENDED);
        assertThat(sessions.period().describe()).isEqualTo("30 days from ENDED");
        assertThat(RetentionCatalog.find("ACCOUNT").orElseThrow().period().describe())
                .isEqualTo("while the account exists");
        assertThat(RetentionCatalog.find("UNKNOWN")).isEmpty();
    }

    @Test
    void aRowBecomesTheItemOfThePolicyTableWithTheLabelAsPeriod() {
        RetentionItem item =
                RetentionCatalog.find("LOGIN_CHALLENGES").orElseThrow().toItem();

        assertThat(item)
                .isEqualTo(new RetentionItem(
                        "Desafios de login", "Verificar a assinatura de entrada", "24 horas após expirar", "Apagar"));
    }

    @Test
    void thePendingListIsEmptySinceU4DeliveredTheDataSubjectRequestPurge() {
        assertThat(PendingRetention.entries()).isEmpty();
        assertThat(PendingRetention.find("DATA_SUBJECT_REQUESTS")).isEmpty();
        assertThat(PendingRetention.find("SESSIONS")).isEmpty();
    }

    @Test
    void aGuardWithAFixedPeriodIsRefused() {
        assertThatThrownBy(() -> new RetentionRule(
                        "BAD_GUARD",
                        1,
                        "Dado",
                        "Finalidade",
                        RetentionPeriod.fixed(RetentionTerm.days(1), RetentionAnchor.CREATED, "1 dia"),
                        "Apagar",
                        Enforcer.guard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BAD_GUARD");
    }

    @Test
    void inconsistentPeriodsEnforcersAndRowsAreRefused() {
        assertThatThrownBy(() -> new RetentionPeriod(
                        RetentionPeriod.Kind.FIXED,
                        java.util.Optional.empty(),
                        java.util.Optional.of(RetentionAnchor.CREATED),
                        "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionPeriod.whileAccountExists(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new Enforcer(Enforcer.Kind.PURGE, java.util.Optional.empty(), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionRule(
                        "lower_case",
                        1,
                        "Dado",
                        "Finalidade",
                        RetentionPeriod.whileAccountExists("x"),
                        "Apagar",
                        Enforcer.guard()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionRule(
                        "NO_ORDER",
                        0,
                        "Dado",
                        "Finalidade",
                        RetentionPeriod.whileAccountExists("x"),
                        "Apagar",
                        Enforcer.guard()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionRule(
                        "NO_TEXT",
                        1,
                        " ",
                        "Finalidade",
                        RetentionPeriod.whileAccountExists("x"),
                        "Apagar",
                        Enforcer.guard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataItem");
    }
}
