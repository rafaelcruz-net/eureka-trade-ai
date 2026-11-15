package com.memetrade.txsigner.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Boundaries of the TxSigner (tech-stack-decisions.md "Fronteiras do TxSigner", NFR1.3): the codec, the policy and the
 * domain depend on neither web nor infrastructure nor the AWS SDK; no production class generates, imports or exports a
 * key; only the KMS adapter talks to the KMS; no floating point; time only from the injected clock.
 */
class TxSignerArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memetrade.txsigner");

    private static final String[] PURE = {
        "com.memetrade.txsigner.codec..", "com.memetrade.txsigner.policy..", "com.memetrade.txsigner.domain.."
    };

    @Test
    void theCodecThePolicyAndTheDomainDependOnNoInfrastructure() {
        noClasses()
                .that()
                .resideInAnyPackage(PURE)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.memetrade.txsigner.infra..",
                        "com.memetrade.txsigner.web..",
                        "org.springframework..",
                        "jakarta.servlet..",
                        "java.sql..",
                        "software.amazon..")
                .because("the policy is a pure component (security-design.md) and the domain has no infrastructure")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void theCodecAndThePolicyNeverDependOnTheDomainServices() {
        noClasses()
                .that()
                .resideInAnyPackage("com.memetrade.txsigner.codec..", "com.memetrade.txsigner.policy..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.memetrade.txsigner.domain..")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void onlyTheKmsAdapterUsesTheAwsSdk() {
        noClasses()
                .that()
                .resideOutsideOfPackages("com.memetrade.txsigner.infra.aws..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("software.amazon..")
                .because("the KMS client lives only in the adapter (tech-stack-decisions.md)")
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void productionCodeNeverGeneratesImportsOrExportsAKey() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.KeyPairGenerator")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.PrivateKey")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.spec.PKCS8EncodedKeySpec")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.KeyStore")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleNameContaining("Mnemonic")
                .because("project.md: no production code generates, reads, imports or exports a private key")
                .check(PRODUCTION);
    }

    @Test
    void theKeyRuleDetectsAViolation() {
        JavaClasses keyMaker = new ClassFileImporter().importClasses(KeyMakerOnlyInTests.class);

        assertThatThrownBy(() -> noClasses()
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("java.security.KeyPairGenerator")
                        .check(keyMaker))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("KeyPairGenerator");
    }

    @Test
    void noFieldOrReturnIsFloatingPoint() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage("com.memetrade.txsigner..")
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .andShould()
                .notHaveRawType(Double.class)
                .andShould()
                .notHaveRawType(Float.class)
                .because("lamports are long with overflow checks (NFR10.1)")
                .allowEmptyShould(true)
                .check(PRODUCTION);
        methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage("com.memetrade.txsigner..")
                .should()
                .notHaveRawReturnType(double.class)
                .andShould()
                .notHaveRawReturnType(float.class)
                .allowEmptyShould(true)
                .check(PRODUCTION);
    }

    @Test
    void timeComesOnlyFromTheInjectedClock() {
        noClasses()
                .should()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .orShould()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(Clock.class, "systemDefaultZone")
                .because("team.md: injectable clock, no now() in the domain")
                .check(PRODUCTION);
    }

    /** Fixture of the detection test; it lives only in test code. */
    static final class KeyMakerOnlyInTests {
        Object make() throws Exception {
            return java.security.KeyPairGenerator.getInstance("Ed25519");
        }
    }
}
