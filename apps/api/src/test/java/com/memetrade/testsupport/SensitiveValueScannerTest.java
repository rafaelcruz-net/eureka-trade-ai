package com.memetrade.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.SensitiveValueScanner.Finding;
import com.memetrade.testsupport.SensitiveValueScanner.Kind;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** BR4.2: the sweep fails on every kind of planted value and reports place and kind, never the value. */
@Tag("u2")
class SensitiveValueScannerTest {

    private static final String ACCESS = "acc.SyntheticAccessToken.0001";
    private static final String REFRESH = "rfr_SyntheticRefreshToken_0002";
    private static final String NONCE = "nonce-SyntheticNonce-0003";
    private static final String SIGNATURE = "SyntheticSignature5u1T7wqA0004";
    private static final String JWT_SHAPED = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";

    private final String address = SyntheticIds.mint();
    private final SensitiveValueScanner scanner = new SensitiveValueScanner()
            .register(Kind.ACCESS_TOKEN, ACCESS)
            .register(Kind.REFRESH_TOKEN, REFRESH)
            .register(Kind.NONCE, NONCE)
            .register(Kind.SIGNATURE, SIGNATURE)
            .register(Kind.WALLET_ADDRESS, address);

    @Test
    void eachPlantedKindIsFoundWithItsPlace() {
        assertThat(scanner.scan("logs", "token " + ACCESS)).containsExactly(new Finding("logs", Kind.ACCESS_TOKEN));
        assertThat(scanner.scan("logs", "cookie " + REFRESH)).containsExactly(new Finding("logs", Kind.REFRESH_TOKEN));
        assertThat(scanner.scan("pg", "n=" + NONCE)).containsExactly(new Finding("pg", Kind.NONCE));
        assertThat(scanner.scan("metrics", SIGNATURE)).containsExactly(new Finding("metrics", Kind.SIGNATURE));
        assertThat(scanner.scan("payload", "{\"actor\":\"" + address + "\"}"))
                .containsExactly(new Finding("payload", Kind.WALLET_ADDRESS));
        assertThat(scanner.scan("details", "{\"x\":\"" + JWT_SHAPED + "\"}"))
                .containsExactly(new Finding("details", Kind.JWT));
        assertThat(scanner.scan("logs", "header Authorization was set"))
                .containsExactly(new Finding("logs", Kind.AUTHORIZATION_HEADER));
        assertThat(scanner.scan("logs", "Bearer abc")).containsExactly(new Finding("logs", Kind.BEARER_PREFIX));
    }

    @Test
    void theReportNamesPlaceAndKindButNeverTheValue() {
        List<Finding> findings = scanner.scan("audit_outbox.payload", ACCESS + " " + NONCE + " Bearer x " + JWT_SHAPED);

        String report = SensitiveValueScanner.report(findings);

        assertThat(report)
                .contains("audit_outbox.payload: ACCESS_TOKEN")
                .contains("audit_outbox.payload: NONCE")
                .contains("audit_outbox.payload: JWT")
                .contains("audit_outbox.payload: BEARER_PREFIX")
                .doesNotContain(ACCESS)
                .doesNotContain(NONCE)
                .doesNotContain(JWT_SHAPED);
    }

    @Test
    void theFullAddressInTheTrailDetailsIsNotAFinding() {
        String details = "{\"walletAddress\":\"" + address + "\"}";

        assertThat(scanner.scan("audit_event.details", details, true)).isEmpty();
        assertThat(scanner.scan("audit_event.details", details + " " + NONCE, true))
                .containsExactly(new Finding("audit_event.details", Kind.NONCE));
        assertThat(scanner.scan("logs", details)).containsExactly(new Finding("logs", Kind.WALLET_ADDRESS));
    }

    @Test
    void cleanTextAndTheWordAuthorizationInsideOtherWordsAreNotFindings() {
        assertThat(scanner.scan("logs", "Audit job finished: job=AUDIT_RETENTION_PURGE, deleted=3"))
                .isEmpty();
        assertThat(scanner.scan("logs", "the request had no authorization; FORBIDDEN_ROLE"))
                .isEmpty();
        assertThat(scanner.scan("logs", "")).isEmpty();
        assertThat(scanner.scan("logs", null)).isEmpty();
        assertThat(SensitiveValueScanner.report(List.of())).isEqualTo("no sensitive value found");
    }

    @Test
    void blankRegisteredValuesAreIgnored() {
        SensitiveValueScanner empty =
                new SensitiveValueScanner().register(Kind.NONCE, " ").register(Kind.NONCE, null);

        assertThat(empty.scan("logs", "any text")).isEmpty();
    }
}
