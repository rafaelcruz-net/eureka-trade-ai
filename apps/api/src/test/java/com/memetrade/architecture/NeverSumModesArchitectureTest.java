package com.memetrade.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.architecture.neversum.MixedModeDtosSample;
import com.memetrade.architecture.neversum.MixedModesSample;
import com.memetrade.pnl.api.LamportsValue;
import com.memetrade.pnl.api.ProratedShare;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealModeTotal;
import com.memetrade.pnl.api.UnownedPnlLine;
import com.memetrade.pnl.application.PaperBotPnlLine;
import com.memetrade.pnl.application.PaperModeBlock;
import com.memetrade.pnl.modeview.sample.AddingModeDtosSample;
import com.memetrade.pnl.modeview.sample.AddingModesSample;
import com.memetrade.pnl.web.ModePnlDtos;
import com.memetrade.pnl.web.PnlController;
import com.memetrade.pnl.web.PnlDtos;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U1, Step 13 (BR5.1, NFR4.1; ADR-003 "NEVER_SUM_MODES"; contract-summary.md "Regras de posse"
 * 7 and 8): no code adds the two modes. Two CLOSED lists of result types, PAPER and REAL, each with its domain types
 * AND its web DTO types (the shapes the route C4 handles); the four parts of the rule:
 *
 * <ol>
 *   <li>no class outside {@code pnl.modeview} depends on a type of each list, except the closed list of ASSEMBLERS of
 *       the route C4 (the controller and the two envelope types), which only place the two blocks side by side;
 *   <li>the classes of {@code pnl.modeview} and the assemblers call no arithmetic;
 *   <li>nor any accessor of a value of a type of the two lists;
 *   <li>each part fails on a sample class made to break it, for the domain types and for the DTO types (the rule is
 *       proven, not assumed).
 * </ol>
 *
 * A new result type of either mode, domain or DTO, enters its list in the same commit (C10, C12, C15).
 */
class NeverSumModesArchitectureTest {

    static final Set<Class<?>> PAPER_DOMAIN = Set.of(PaperModeBlock.class, PaperBotPnlLine.class);

    static final Set<Class<?>> REAL_DOMAIN = Set.of(
            RealModePnl.class,
            RealBotPnlLine.class,
            RealModeTotal.class,
            UnownedPnlLine.class,
            ProratedShare.class,
            LamportsValue.class,
            LamportsValue.Known.class,
            LamportsValue.Unknown.class);

    /** The PAPER block on the wire, with its US$ amount shapes (PnlAmountDto is used by PAPER code only). */
    static final Set<Class<?>> PAPER_DTOS = Set.of(
            ModePnlDtos.PaperModeBlockResultDto.class,
            ModePnlDtos.PaperModeBlockDto.class,
            ModePnlDtos.PaperBotPnlLineDto.class,
            PnlDtos.PnlAmountDto.class,
            PnlDtos.KnownPnlDto.class,
            PnlDtos.UnknownPnlDto.class);

    /** The REAL block on the wire, with its lamport amount shapes. */
    static final Set<Class<?>> REAL_DTOS = Set.of(
            ModePnlDtos.RealModeBlockResultDto.class,
            ModePnlDtos.RealModeBlockDto.class,
            ModePnlDtos.RealBotPnlLineDto.class,
            ModePnlDtos.UnownedPnlLineDto.class,
            ModePnlDtos.ProratedSharesDto.class,
            ModePnlDtos.ProratedSummaryDto.class,
            ModePnlDtos.ProratedLineDto.class,
            ModePnlDtos.LamportsAmountDto.class,
            ModePnlDtos.LamportsKnownDto.class,
            ModePnlDtos.LamportsUnknownDto.class);

    /** The types of ModePnlDtos that carry no value of either mode. */
    static final Set<Class<?>> NEUTRAL_DTOS = Set.of(
            ModePnlDtos.class,
            ModePnlDtos.PnlByModeDto.class,
            ModePnlDtos.PnlPeriodDto.class,
            ModePnlDtos.ModeBlockUnavailableDto.class);

    /**
     * CLOSED list of the classes outside the mode view allowed to see both modes: they only put the two blocks in one
     * envelope. Parts 2 and 3 hold for them as for the mode view.
     */
    static final Set<Class<?>> ASSEMBLERS =
            Set.of(PnlController.class, ModePnlDtos.PnlByModeDto.class, ModePnlDtos.ModeBlockUnavailableDto.class);

    static final Set<Class<?>> PAPER_RESULTS = union(PAPER_DOMAIN, PAPER_DTOS);

    static final Set<Class<?>> REAL_RESULTS = union(REAL_DOMAIN, REAL_DTOS);

    static final String MODE_VIEW = "com.memetrade.pnl.modeview..";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade");

    /** Test classes made to break each part: one outside the mode view, one inside it (test sources only). */
    private static final JavaClasses DOMAIN_SAMPLES =
            new ClassFileImporter().importClasses(MixedModesSample.class, AddingModesSample.class);

    /** The same, on the DTO types of the route C4 only. */
    private static final JavaClasses DTO_SAMPLES =
            new ClassFileImporter().importClasses(MixedModeDtosSample.class, AddingModeDtosSample.class);

    @Test
    void part1NoClassOutsideTheModeViewDependsOnBothModes() {
        part1().check(PRODUCTION);
    }

    @Test
    void part2TheModeViewAndTheAssemblersCallNoArithmetic() {
        part2().check(PRODUCTION);
    }

    @Test
    void part3TheModeViewAndTheAssemblersCallNoAccessorOfAValueOfEitherMode() {
        part3().check(PRODUCTION);
    }

    @Test
    void part4EachPartFailsOnADomainSampleMadeToBreakIt() {
        assertThatThrownBy(() -> part1().check(DOMAIN_SAMPLES)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> part2().check(DOMAIN_SAMPLES)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> part3().check(DOMAIN_SAMPLES)).isInstanceOf(AssertionError.class);
    }

    @Test
    void part4EachPartFailsOnADtoSampleMadeToBreakIt() {
        assertThatThrownBy(() -> part1().check(DTO_SAMPLES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(MixedModeDtosSample.class.getName());
        assertThatThrownBy(() -> part2().check(DTO_SAMPLES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(AddingModeDtosSample.class.getName());
        assertThatThrownBy(() -> part3().check(DTO_SAMPLES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(AddingModeDtosSample.class.getName());
    }

    @Test
    void theListsAreClosedAndDisjoint() {
        assertThat(PAPER_RESULTS).doesNotContainAnyElementsOf(REAL_RESULTS);
        assertThat(PRODUCTION.stream()
                        .filter(type -> type.getPackageName().equals("com.memetrade.pnl.api"))
                        .filter(type -> type.getSimpleName().startsWith("Real")
                                && !type.getSimpleName().endsWith("Query")
                                && !type.getSimpleName().endsWith("Unavailable"))
                        .map(JavaClass::reflect)
                        .collect(Collectors.toSet()))
                .as("every REAL result type of pnl.api is on the REAL list")
                .isSubsetOf(REAL_RESULTS);
        Set<Class<?>> modeDtos = Stream.concat(
                        Stream.of(ModePnlDtos.class), Arrays.stream(ModePnlDtos.class.getDeclaredClasses()))
                .collect(Collectors.toSet());
        assertThat(union(union(PAPER_DTOS, REAL_DTOS), NEUTRAL_DTOS))
                .as("every type of ModePnlDtos is on the PAPER, the REAL or the neutral list")
                .containsAll(modeDtos);
        assertThat(NEUTRAL_DTOS).doesNotContainAnyElementsOf(PAPER_RESULTS).doesNotContainAnyElementsOf(REAL_RESULTS);
    }

    static ArchRule part1() {
        return classes()
                .that()
                .resideOutsideOfPackage(MODE_VIEW)
                .should(new ArchCondition<JavaClass>("depend on result types of at most one mode") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        Class<?> self = reflectOrNull(type);
                        if (ASSEMBLERS.contains(self)) {
                            return;
                        }
                        Set<Class<?>> targets = type.getDirectDependenciesFromSelf().stream()
                                .map(dependency -> dependency.getTargetClass())
                                .filter(target -> !target.isPrimitive() && !target.isArray())
                                .map(NeverSumModesArchitectureTest::reflectOrNull)
                                .filter(java.util.Objects::nonNull)
                                .collect(Collectors.toSet());
                        boolean paper =
                                targets.stream().anyMatch(PAPER_RESULTS::contains) || PAPER_RESULTS.contains(self);
                        boolean real = targets.stream().anyMatch(REAL_RESULTS::contains) || REAL_RESULTS.contains(self);
                        if (paper && real) {
                            events.add(SimpleConditionEvent.violated(
                                    type, type.getName() + " sees PAPER and REAL results together (NEVER_SUM_MODES)"));
                        }
                    }
                })
                .allowEmptyShould(true)
                .because("only pnl.modeview and the C4 assemblers put the two modes side by side, and they compute"
                        + " nothing (ADR-003)");
    }

    static ArchRule part2() {
        return noClasses()
                .that(modeViewOrAssembler())
                .should()
                .callMethodWhere(arithmetic())
                .allowEmptyShould(true)
                .because("the mode view and the C4 assemblers never add, subtract nor convert a value (ADR-003)");
    }

    static ArchRule part3() {
        return noClasses()
                .that(modeViewOrAssembler())
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("an accessor of a PAPER or REAL result") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        Class<?> owner = reflectOrNull(call.getTargetOwner());
                        return PAPER_RESULTS.contains(owner) || REAL_RESULTS.contains(owner);
                    }
                })
                .allowEmptyShould(true)
                .because("the mode view and the C4 assemblers never read a value of either mode (ADR-003)");
    }

    private static DescribedPredicate<JavaClass> modeViewOrAssembler() {
        return new DescribedPredicate<>("reside in pnl.modeview or are a C4 assembler") {
            @Override
            public boolean test(JavaClass type) {
                return type.getPackageName().startsWith("com.memetrade.pnl.modeview")
                        || ASSEMBLERS.contains(reflectOrNull(type));
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> arithmetic() {
        List<String> exact = List.of("addExact", "subtractExact", "multiplyExact", "negateExact");
        List<String> decimal = List.of("add", "subtract", "multiply", "divide", "negate", "pow", "remainder");
        return new DescribedPredicate<>("an arithmetic operation on money") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getName();
                String name = call.getName();
                return (owner.equals(Math.class.getName()) && exact.contains(name))
                        || ((owner.equals(BigDecimal.class.getName()) || owner.equals(BigInteger.class.getName()))
                                && decimal.contains(name));
            }
        };
    }

    private static Set<Class<?>> union(Set<Class<?>> first, Set<Class<?>> second) {
        Set<Class<?>> all = new HashSet<>(first);
        all.addAll(second);
        return Set.copyOf(all);
    }

    private static Class<?> reflectOrNull(JavaClass type) {
        try {
            return type.reflect();
        } catch (RuntimeException | LinkageError unavailable) {
            // A class of a library not on the test class path cannot be one of the listed result types.
            return null;
        }
    }
}
