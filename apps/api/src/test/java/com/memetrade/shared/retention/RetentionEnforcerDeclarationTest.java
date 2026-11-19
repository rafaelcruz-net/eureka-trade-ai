package com.memetrade.shared.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.RetentionDeclarations;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 of u2-retention (BR2.17, J7; tests written after this layer): every purge declares, from the property it uses,
 * the period it applies and its anchor; the declarations of the modules read the real {@code application.yml}; a
 * changed property changes the declaration; the decision purge of the worker is declared in the api context too.
 */
@Tag("u2-retention")
class RetentionEnforcerDeclarationTest {

    @Test
    void everyPurgeOfTheCodeDeclaresItsPeriodAndAnchorFromApplicationYml() {
        Map<String, RetentionEnforcerDeclaration> declared = byId(RetentionDeclarations.fromApplicationYml());

        assertThat(declared)
                .containsOnlyKeys(
                        "auditRetention",
                        "auditOutboxCleanup",
                        "feedbackPurge",
                        "decisionPurge",
                        "riskcontrol-purge",
                        "idempotencyCleanup",
                        "loginChallengePurge",
                        "sessionPurge",
                        // u4-data-requests (BR4.15).
                        "dataSubjectRequestPurge",
                        // 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7).
                        "real-risk-purge");
        assertDeclared(declared, "real-risk-purge", RetentionTerm.months(13), RetentionAnchor.CREATED);
        assertDeclared(declared, "dataSubjectRequestPurge", RetentionTerm.months(12), RetentionAnchor.CONCLUDED);
        assertDeclared(declared, "auditRetention", RetentionTerm.months(12), RetentionAnchor.CREATED);
        assertDeclared(declared, "auditOutboxCleanup", RetentionTerm.days(7), RetentionAnchor.DELIVERED);
        assertDeclared(declared, "feedbackPurge", RetentionTerm.days(180), RetentionAnchor.CREATED);
        assertDeclared(declared, "decisionPurge", RetentionTerm.days(30), RetentionAnchor.CREATED);
        assertDeclared(declared, "riskcontrol-purge", RetentionTerm.days(30), RetentionAnchor.CREATED);
        assertDeclared(declared, "idempotencyCleanup", RetentionTerm.hours(24), RetentionAnchor.CREATED);
        assertDeclared(declared, "loginChallengePurge", RetentionTerm.hours(24), RetentionAnchor.EXPIRED);
        assertDeclared(declared, "sessionPurge", RetentionTerm.days(30), RetentionAnchor.ENDED);
    }

    @Test
    void aChangedPropertyChangesTheDeclarationOfItsPurge() {
        RetentionDeclarations.context()
                .withPropertyValues(
                        "memetrade.identity.purge.session-retention=45d",
                        "memetrade.admin.feedback.retention=90d",
                        "memetrade.audit.jobs.retention-months=18")
                .run(context -> {
                    Map<String, RetentionEnforcerDeclaration> declared =
                            byId(List.copyOf(context.getBeansOfType(RetentionEnforcerDeclaration.class)
                                    .values()));
                    assertThat(declared.get("sessionPurge").retention()).isEqualTo(RetentionTerm.days(45));
                    assertThat(declared.get("feedbackPurge").retention()).isEqualTo(RetentionTerm.days(90));
                    assertThat(declared.get("auditRetention").retention()).isEqualTo(RetentionTerm.months(18));
                });
    }

    @Test
    void theDecisionPurgeOfTheWorkerIsDeclaredInTheApiProcess() {
        RetentionDeclarations.context()
                .withPropertyValues("memetrade.process=api")
                .run(context -> assertThat(context).hasBean("decisionPurgeDeclaration"));
    }

    @Test
    void aTermNamesTheSameSpanWhateverTheUnitOfDays() {
        assertThat(RetentionTerm.days(1)).isEqualTo(RetentionTerm.hours(24));
        assertThat(RetentionTerm.of(Duration.ofDays(30))).isEqualTo(RetentionTerm.days(30));
        assertThat(RetentionTerm.of(Duration.ofHours(24))).isEqualTo(RetentionTerm.hours(24));
        assertThat(RetentionTerm.months(12)).isNotEqualTo(RetentionTerm.days(365));
    }

    @Test
    void aTermIsPrintedInTheWordsOfTheConformanceMessages() {
        assertThat(RetentionTerm.months(12)).hasToString("12 months");
        assertThat(RetentionTerm.months(1)).hasToString("1 month");
        assertThat(RetentionTerm.days(30)).hasToString("30 days");
        assertThat(RetentionTerm.hours(24)).hasToString("1 day");
        assertThat(RetentionTerm.hours(36)).hasToString("36 hours");
        assertThat(RetentionTerm.hours(1)).hasToString("1 hour");
    }

    @Test
    void aTermMustBeAPositiveWholeNumberOfHours() {
        assertThatThrownBy(() -> RetentionTerm.hours(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionTerm.of(Duration.ofMinutes(90))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionTerm.of(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionTerm.of(Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeclarationNeedsItsPurgeIdPeriodAndAnchor() {
        assertThatThrownBy(() -> RetentionEnforcerDeclaration.of(" ", RetentionTerm.days(1), RetentionAnchor.CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionEnforcerDeclaration.of("x", null, RetentionAnchor.CREATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RetentionEnforcerDeclaration.of("x", RetentionTerm.days(1), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static void assertDeclared(
            Map<String, RetentionEnforcerDeclaration> declared,
            String purgeId,
            RetentionTerm retention,
            RetentionAnchor anchor) {
        assertThat(declared.get(purgeId).retention()).as(purgeId).isEqualTo(retention);
        assertThat(declared.get(purgeId).anchor()).as(purgeId).isEqualTo(anchor);
    }

    private static Map<String, RetentionEnforcerDeclaration> byId(List<RetentionEnforcerDeclaration> declarations) {
        return declarations.stream()
                .collect(Collectors.toMap(RetentionEnforcerDeclaration::purgeId, Function.identity()));
    }
}
