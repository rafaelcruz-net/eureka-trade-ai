package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.domain.legal.LegalTextParser;
import com.memetrade.privacy.domain.PendingRetention;
import com.memetrade.privacy.domain.RetentionCatalog;
import com.memetrade.privacy.domain.RetentionConformance;
import com.memetrade.privacy.domain.RetentionConformance.Divergence;
import com.memetrade.privacy.domain.RetentionRule;
import com.memetrade.shared.retention.RetentionAnchor;
import com.memetrade.shared.retention.RetentionEnforcerDeclaration;
import com.memetrade.shared.retention.RetentionTerm;
import com.memetrade.testsupport.RetentionDeclarations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 9 of u2-retention (US1.4; AC1.4.1, AC1.4.2; BR2.10 to BR2.12): the retention catalog of the Privacy module
 * holds against what the code applies.
 *
 * <ul>
 *   <li>each PURGE row has the declaration (J7) of its purge with the same period and anchor, read from the real
 *       {@code application.yml};
 *   <li>GUARD holds only "while the account exists"; PLANNED only through the pending list and without a purge yet;
 *   <li>the {@code retention.tsv} of every non-legacy version of the legal catalog equals the catalog, row by row.
 * </ul>
 *
 * A divergence fails naming the row, the period the catalog declares and the period the code applies; the tests that
 * change a period on purpose show the check is not vacuous.
 */
@Tag("u2-retention")
class RetentionConformanceTest {

    private static final String LEGAL_CATALOG = "classpath:terms/";

    @Test
    void theCatalogConformsToThePurgesOfTheCodeAndToThePendingList() {
        List<Divergence> divergences = RetentionConformance.check(
                RetentionCatalog.rules(), RetentionDeclarations.fromApplicationYml(), PendingRetention.entries());

        assertThat(divergences).as(report(divergences)).isEmpty();
    }

    @Test
    void aPurgePeriodChangedOnPurposeFailsNamingTheRowAndBothPeriods() {
        List<RetentionEnforcerDeclaration> changed = new ArrayList<>();
        RetentionDeclarations.context()
                .withPropertyValues("memetrade.identity.purge.session-retention=45d")
                .run(context -> changed.addAll(context.getBeansOfType(RetentionEnforcerDeclaration.class)
                        .values()));

        List<Divergence> divergences =
                RetentionConformance.check(RetentionCatalog.rules(), changed, PendingRetention.entries());

        assertThat(divergences).hasSize(1);
        Divergence divergence = divergences.getFirst();
        assertThat(divergence.ruleId()).isEqualTo("SESSIONS");
        assertThat(divergence.declared()).isEqualTo("30 days from ENDED");
        assertThat(divergence.applied()).isEqualTo("45 days from ENDED (sessionPurge)");
        assertThat(divergence.toString())
                .isEqualTo("Retention row SESSIONS: the catalog declares 30 days from ENDED, the code applies 45 days"
                        + " from ENDED (sessionPurge)");
    }

    @Test
    void anotherAnchorIsADivergenceEvenWithTheSamePeriod() {
        List<RetentionEnforcerDeclaration> declarations = replace(
                RetentionDeclarations.fromApplicationYml(),
                RetentionEnforcerDeclaration.of(
                        "loginChallengePurge", RetentionTerm.hours(24), RetentionAnchor.CREATED));

        List<Divergence> divergences =
                RetentionConformance.check(RetentionCatalog.rules(), declarations, PendingRetention.entries());

        assertThat(divergences).singleElement().satisfies(divergence -> {
            assertThat(divergence.ruleId()).isEqualTo("LOGIN_CHALLENGES");
            assertThat(divergence.declared()).isEqualTo("1 day from EXPIRED");
            assertThat(divergence.applied()).isEqualTo("1 day from CREATED (loginChallengePurge)");
        });
    }

    @Test
    void aPurgeRowWithoutTheDeclarationOfItsPurgeFails() {
        List<RetentionEnforcerDeclaration> withoutFeedback = RetentionDeclarations.fromApplicationYml().stream()
                .filter(declaration -> !declaration.purgeId().equals("feedbackPurge"))
                .toList();

        List<Divergence> divergences =
                RetentionConformance.check(RetentionCatalog.rules(), withoutFeedback, PendingRetention.entries());

        assertThat(divergences).singleElement().satisfies(divergence -> {
            assertThat(divergence.ruleId()).isEqualTo("FEEDBACK");
            assertThat(divergence.applied()).contains("no declaration of the purge feedbackPurge");
        });
    }

    /**
     * u4-data-requests: the catalog has no PLANNED row any more, so the PLANNED checks run on the catalog as it was
     * before this Unit (DATA_SUBJECT_REQUESTS planned for u4-data-requests), which keeps them meaningful for a later Unit.
     */
    private static List<RetentionRule> catalogWithPlannedDataRequests() {
        List<RetentionRule> rules = new ArrayList<>();
        for (RetentionRule rule : RetentionCatalog.rules()) {
            rules.add(
                    rule.id().equals("DATA_SUBJECT_REQUESTS")
                            ? new RetentionRule(
                                    rule.id(),
                                    rule.order(),
                                    rule.dataItem(),
                                    rule.purpose(),
                                    rule.period(),
                                    rule.endAction(),
                                    com.memetrade.privacy.domain.Enforcer.planned("u4-data-requests"))
                            : rule);
        }
        return rules;
    }

    private static List<RetentionEnforcerDeclaration> withoutRequestPurge() {
        return RetentionDeclarations.fromApplicationYml().stream()
                .filter(declaration -> !declaration.purgeId().equals("dataSubjectRequestPurge"))
                .toList();
    }

    @Test
    void aPlannedRowOutsideThePendingListFails() {
        List<Divergence> divergences =
                RetentionConformance.check(catalogWithPlannedDataRequests(), withoutRequestPurge(), List.of());

        assertThat(divergences).singleElement().satisfies(divergence -> {
            assertThat(divergence.ruleId()).isEqualTo("DATA_SUBJECT_REQUESTS");
            assertThat(divergence.applied()).contains("not in the pending list");
        });
    }

    @Test
    void aPendingRowWhosePurgeAlreadyExistsFails() {
        List<PendingRetention.Entry> pending = List.of(
                new PendingRetention.Entry("DATA_SUBJECT_REQUESTS", "u4-data-requests", "dataSubjectRequestPurge"));

        List<Divergence> divergences = RetentionConformance.check(
                catalogWithPlannedDataRequests(), RetentionDeclarations.fromApplicationYml(), pending);

        assertThat(divergences).singleElement().satisfies(divergence -> {
            assertThat(divergence.ruleId()).isEqualTo("DATA_SUBJECT_REQUESTS");
            assertThat(divergence.applied()).contains("dataSubjectRequestPurge already exists");
        });
    }

    @Test
    void theRequestPurgeDeclaresTwelveMonthsFromTheConclusionAndNoRowIsPendingAnyMore() {
        assertThat(RetentionDeclarations.fromApplicationYml())
                .filteredOn(declaration -> declaration.purgeId().equals("dataSubjectRequestPurge"))
                .singleElement()
                .satisfies(declaration -> {
                    assertThat(declaration.retention()).isEqualTo(RetentionTerm.months(12));
                    assertThat(declaration.anchor()).isEqualTo(RetentionAnchor.CONCLUDED);
                });
        assertThat(PendingRetention.entries()).isEmpty();
    }

    @Test
    void theRequestRowWithoutItsPurgeFailsNamingThePurge() {
        List<Divergence> divergences =
                RetentionConformance.check(RetentionCatalog.rules(), withoutRequestPurge(), PendingRetention.entries());

        assertThat(divergences).singleElement().satisfies(divergence -> {
            assertThat(divergence.ruleId()).isEqualTo("DATA_SUBJECT_REQUESTS");
            assertThat(divergence.applied()).contains("no declaration of the purge dataSubjectRequestPurge");
        });
    }

    @Test
    void aRequestRetentionChangedOnPurposeFails() {
        List<RetentionEnforcerDeclaration> changed = new ArrayList<>();
        RetentionDeclarations.context()
                .withPropertyValues("memetrade.privacy.retention-months=6")
                .run(context -> changed.addAll(context.getBeansOfType(RetentionEnforcerDeclaration.class)
                        .values()));

        List<Divergence> divergences =
                RetentionConformance.check(RetentionCatalog.rules(), changed, PendingRetention.entries());

        assertThat(divergences).singleElement().satisfies(divergence -> {
            assertThat(divergence.ruleId()).isEqualTo("DATA_SUBJECT_REQUESTS");
            assertThat(divergence.declared()).isEqualTo("12 months from CONCLUDED");
            assertThat(divergence.applied()).isEqualTo("6 months from CONCLUDED (dataSubjectRequestPurge)");
        });
    }

    @Test
    void aPendingEntryForARowThatIsNotPlannedFailsAndSoDoesATwiceDeclaredPurge() {
        List<PendingRetention.Entry> pending = new ArrayList<>(PendingRetention.entries());
        pending.add(new PendingRetention.Entry("SESSIONS", "u9-later", "sessionPurge"));
        List<RetentionEnforcerDeclaration> declarations = new ArrayList<>(RetentionDeclarations.fromApplicationYml());
        declarations.add(
                RetentionEnforcerDeclaration.of("sessionPurge", RetentionTerm.days(30), RetentionAnchor.ENDED));

        List<Divergence> divergences = RetentionConformance.check(RetentionCatalog.rules(), declarations, pending);

        assertThat(divergences).extracting(Divergence::ruleId).containsExactlyInAnyOrder("sessionPurge", "SESSIONS");
    }

    @Test
    void theRetentionTableOfEveryNonLegacyVersionEqualsTheCatalogOrIsAnEarlierPrefixOfIt() throws IOException {
        List<String> current = new ArrayList<>();
        List<String> earlier = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Resource descriptor :
                new PathMatchingResourcePatternResolver().getResources(LEGAL_CATALOG + "*/version.properties")) {
            Properties version = properties(descriptor);
            if (Boolean.parseBoolean(version.getProperty("legacy", "false").trim())) {
                continue;
            }
            String name = version.getProperty("version").trim();
            List<RetentionItem> table = LegalTextParser.retention(
                    text(descriptor.createRelative("retention.tsv")), "retention.tsv of " + name);
            // 261113-trading-real-bot U6, Step 19 (review-03 R-12): the version marked current (v3) equals the catalog
            // row by row; an earlier non-legacy version (the draft v2, never published) is never edited, so it must be
            // the catalog as it was then: an in-order prefix of it, word for word.
            if (Boolean.parseBoolean(version.getProperty("current", "false").trim())) {
                current.add(name);
                problems.addAll(tableDivergences(name, table));
            } else {
                earlier.add(name);
                problems.addAll(prefixDivergences(name, table));
            }
        }

        assertThat(current).as("the non-legacy version marked current").containsExactly("v3");
        assertThat(earlier).as("earlier non-legacy versions").contains("v2");
        assertThat(problems).as(String.join("\n", problems)).isEmpty();
    }

    @Test
    void anEarlierTableThatIsNotAPrefixOfTheCatalogIsReported() {
        List<RetentionItem> table = new ArrayList<>(catalogItems().subList(0, 13));
        RetentionItem sessions = table.get(10);
        table.set(10, new RetentionItem(sessions.dataItem(), sessions.purpose(), "60 dias", sessions.endAction()));

        assertThat(prefixDivergences("v8", catalogItems().subList(0, 13))).isEmpty();
        assertThat(prefixDivergences("v8", table))
                .containsExactly(
                        "v8 row 11 (SESSIONS): period is '60 dias'; the catalog says '30 dias após o fim ou a revogação'");
        List<RetentionItem> longer = new ArrayList<>(catalogItems());
        longer.add(new RetentionItem("Dado a mais", "Finalidade", "1 dia", "Apagar"));
        assertThat(prefixDivergences("v8", longer))
                .containsExactly("v8 has " + longer.size() + " retention rows; the catalog has only "
                        + catalogItems().size());
    }

    @Test
    void aTableThatDiffersFromTheCatalogIsReportedByVersionAndRow() {
        List<RetentionItem> table = new ArrayList<>(catalogItems());
        RetentionItem sessions = table.get(10);
        table.set(10, new RetentionItem(sessions.dataItem(), sessions.purpose(), "60 dias", sessions.endAction()));
        table.removeLast();

        List<String> problems = tableDivergences("v9", table);

        assertThat(problems)
                .containsExactly(
                        "v9 has " + (catalogItems().size() - 1) + " retention rows; the catalog has "
                                + catalogItems().size(),
                        "v9 row 11 (SESSIONS): period is '60 dias'; the catalog says '30 dias após o fim ou a revogação'");
    }

    /**
     * 261113-trading-real-bot U3, loop-back 1 of Build and Test (U3-NFR3.7): the row of the REAL barrier conforms to the
     * declaration of {@code real-risk-purge}, and the policy table of v2 carries it with the same words.
     */
    @Test
    void theRealBarrierRowConformsToItsPurgeAndIsInThePolicyTable() throws IOException {
        assertThat(RetentionDeclarations.fromApplicationYml())
                .filteredOn(declaration -> declaration.purgeId().equals("real-risk-purge"))
                .singleElement()
                .satisfies(declaration -> {
                    assertThat(declaration.retention()).isEqualTo(RetentionTerm.months(13));
                    assertThat(declaration.anchor()).isEqualTo(RetentionAnchor.CREATED);
                });
        RetentionItem expected =
                RetentionCatalog.find("REAL_RISK_EVALUATIONS").orElseThrow().toItem();
        Resource v2 = new PathMatchingResourcePatternResolver().getResource(LEGAL_CATALOG + "v2/retention.tsv");
        assertThat(LegalTextParser.retention(text(v2), "retention.tsv of v2")).contains(expected);
    }

    /**
     * 261113-trading-real-bot U6, Step 19 (Build and Test loop-back 2): the table of v3 keeps every row of v2 in the
     * same order and words, the REAL barrier row included, and so stays in parity with the catalog.
     */
    @Test
    void theTableOfV3KeepsEveryRowOfV2IncludingTheRealBarrierRowAndAddsTheRealRows() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<RetentionItem> v2 =
                LegalTextParser.retention(text(resolver.getResource(LEGAL_CATALOG + "v2/retention.tsv")), "v2");
        List<RetentionItem> v3 =
                LegalTextParser.retention(text(resolver.getResource(LEGAL_CATALOG + "v3/retention.tsv")), "v3");

        assertThat(v3.subList(0, v2.size())).containsExactlyElementsOf(v2);
        assertThat(v3)
                .contains(RetentionCatalog.find("REAL_RISK_EVALUATIONS")
                        .orElseThrow()
                        .toItem());
        // review-03 R-12: every datum of the REAL that the policy v3 lists has its row.
        for (String id : List.of(
                "REAL_BOT_WALLET",
                "REAL_LEDGER",
                "REAL_ORDERS",
                "REAL_DECISION_REQUESTS",
                "REAL_CONDITIONAL_EXITS",
                "REAL_RELEASES",
                "REAL_RECONCILIATIONS",
                "REAL_DIVERGENCES",
                "REAL_TXSIGNER_COMMANDS")) {
            assertThat(v3)
                    .as(id)
                    .contains(RetentionCatalog.find(id).orElseThrow().toItem());
        }
        assertThat(tableDivergences("v3", v3)).isEmpty();
    }

    @Test
    void theRealRowWithoutItsPurgeOrWithAnotherPeriodFails() {
        List<RetentionEnforcerDeclaration> without = RetentionDeclarations.fromApplicationYml().stream()
                .filter(declaration -> !declaration.purgeId().equals("real-risk-purge"))
                .toList();
        List<RetentionEnforcerDeclaration> shorter = replace(
                RetentionDeclarations.fromApplicationYml(),
                RetentionEnforcerDeclaration.of("real-risk-purge", RetentionTerm.months(12), RetentionAnchor.CREATED));

        assertThat(RetentionConformance.check(RetentionCatalog.rules(), without, PendingRetention.entries()))
                .singleElement()
                .satisfies(divergence -> {
                    assertThat(divergence.ruleId()).isEqualTo("REAL_RISK_EVALUATIONS");
                    assertThat(divergence.applied()).contains("no declaration of the purge real-risk-purge");
                });
        assertThat(RetentionConformance.check(RetentionCatalog.rules(), shorter, PendingRetention.entries()))
                .singleElement()
                .satisfies(divergence -> {
                    assertThat(divergence.declared()).isEqualTo("13 months from CREATED");
                    assertThat(divergence.applied()).isEqualTo("12 months from CREATED (real-risk-purge)");
                });
    }

    /** BR2.11: the rows of a version, compared with the catalog in order, field by field. */
    private static List<String> tableDivergences(String version, List<RetentionItem> table) {
        List<RetentionRule> rules = RetentionCatalog.rules();
        List<String> problems = new ArrayList<>();
        if (table.size() != rules.size()) {
            problems.add(version + " has " + table.size() + " retention rows; the catalog has " + rules.size());
        }
        for (int index = 0; index < Math.min(table.size(), rules.size()); index++) {
            RetentionItem expected = rules.get(index).toItem();
            RetentionItem actual = table.get(index);
            String row =
                    version + " row " + (index + 1) + " (" + rules.get(index).id() + "): ";
            compare(problems, row, "dataItem", actual.dataItem(), expected.dataItem());
            compare(problems, row, "purpose", actual.purpose(), expected.purpose());
            compare(problems, row, "period", actual.period(), expected.period());
            compare(problems, row, "endAction", actual.endAction(), expected.endAction());
        }
        return problems;
    }

    /** An earlier version: at most as many rows as the catalog, each equal to the catalog row of the same position. */
    private static List<String> prefixDivergences(String version, List<RetentionItem> table) {
        List<RetentionRule> rules = RetentionCatalog.rules();
        if (table.size() > rules.size()) {
            return List.of(version + " has " + table.size() + " retention rows; the catalog has only " + rules.size());
        }
        return tableDivergences(version, table).stream()
                .filter(problem -> !problem.startsWith(version + " has "))
                .toList();
    }

    private static void compare(List<String> problems, String row, String field, String actual, String expected) {
        if (!actual.equals(expected)) {
            problems.add(row + field + " is '" + actual + "'; the catalog says '" + expected + "'");
        }
    }

    private static List<RetentionItem> catalogItems() {
        return RetentionCatalog.rules().stream().map(RetentionRule::toItem).toList();
    }

    private static List<RetentionEnforcerDeclaration> replace(
            List<RetentionEnforcerDeclaration> declarations, RetentionEnforcerDeclaration replacement) {
        List<RetentionEnforcerDeclaration> result = new ArrayList<>();
        for (RetentionEnforcerDeclaration declaration : declarations) {
            result.add(declaration.purgeId().equals(replacement.purgeId()) ? replacement : declaration);
        }
        return result;
    }

    private static String report(List<Divergence> divergences) {
        return divergences.stream().map(Divergence::toString).collect(Collectors.joining("\n"));
    }

    private static Properties properties(Resource resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = resource.getInputStream()) {
            properties.load(in);
        }
        return properties;
    }

    private static String text(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
