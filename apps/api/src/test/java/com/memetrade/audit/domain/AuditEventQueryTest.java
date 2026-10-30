package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR2.2 and BR2.3: strict parameters, a period of at most 31 days, limit 1-100 and the opaque cursor. */
@Tag("u2")
class AuditEventQueryTest {

    private static final String FROM = "2026-10-01T00:00:00Z";

    @Test
    void aValidQueryKeepsItsValuesAndDefaultsTheLimitTo50() {
        AuditEventQuery query = AuditEventQuery.fromParameters(params("from", FROM, "to", "2026-10-08T00:00:00Z"));

        assertThat(query.from()).isEqualTo(Instant.parse(FROM));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(query.action()).isEmpty();
        assertThat(query.cursor()).isEmpty();
        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.rowsToRead()).isEqualTo(51);
    }

    @Test
    void exactly31DaysIsAcceptedAndOneMillisecondMoreIsRefusedOnTo() {
        assertThat(AuditEventQuery.fromParameters(params("from", FROM, "to", "2026-11-01T00:00:00Z"))
                        .to())
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));

        assertViolations(params("from", FROM, "to", "2026-11-01T00:00:00.001Z"), "to");
    }

    @Test
    void toMustBeAfterFrom() {
        assertViolations(params("from", FROM, "to", FROM), "to");
        assertViolations(params("from", FROM, "to", "2026-09-30T00:00:00Z"), "to");
    }

    @Test
    void fromAndToAreRequiredAndEveryMissingFieldIsListed() {
        assertViolations(Map.of(), "from", "to");
        assertViolations(params("from", "yesterday", "to", "2026-10-02"), "from", "to");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void limitsAtTheBoundariesAreAccepted(String limit) {
        assertThat(AuditEventQuery.fromParameters(params("from", FROM, "to", "2026-10-02T00:00:00Z", "limit", limit))
                        .limit())
                .isEqualTo(Integer.parseInt(limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "ten"})
    void limitsOutside1To100AreRefused(String limit) {
        assertViolations(params("from", FROM, "to", "2026-10-02T00:00:00Z", "limit", limit), "limit");
    }

    @Test
    void anActionMustFollowTheUpperCaseFormatAndFiltersByEquality() {
        assertThat(AuditEventQuery.fromParameters(
                                params("from", FROM, "to", "2026-10-02T00:00:00Z", "action", "LOGIN_REJECTED"))
                        .action())
                .contains("LOGIN_REJECTED");
        assertViolations(params("from", FROM, "to", "2026-10-02T00:00:00Z", "action", "login_rejected"), "action");
        assertViolations(params("from", FROM, "to", "2026-10-02T00:00:00Z", "action", "A".repeat(65)), "action");
    }

    @Test
    void anUndeclaredOrRepeatedParameterIsRefused() {
        Map<String, List<String>> unknown = params("from", FROM, "to", "2026-10-02T00:00:00Z", "tenantId", "x");
        assertViolations(unknown, "tenantId");

        Map<String, List<String>> repeated = new LinkedHashMap<>(params("to", "2026-10-02T00:00:00Z"));
        repeated.put("from", List.of(FROM, FROM));
        assertViolations(repeated, "from");
    }

    @Test
    void aCursorInsideThePeriodIsDecoded() {
        AuditCursor cursor = new AuditCursor(Instant.parse("2026-10-01T10:00:00Z"), UUID.randomUUID());

        AuditEventQuery query = AuditEventQuery.fromParameters(
                params("from", FROM, "to", "2026-10-02T00:00:00Z", "cursor", cursor.encode()));

        assertThat(query.cursor()).contains(cursor);
    }

    @Test
    void aCursorOutsideThePeriodOrMalformedIsRefused() {
        AuditCursor before = new AuditCursor(Instant.parse("2026-09-30T23:59:59Z"), UUID.randomUUID());
        AuditCursor atTo = new AuditCursor(Instant.parse("2026-10-02T00:00:00Z"), UUID.randomUUID());

        assertViolations(params("from", FROM, "to", "2026-10-02T00:00:00Z", "cursor", before.encode()), "cursor");
        assertViolations(params("from", FROM, "to", "2026-10-02T00:00:00Z", "cursor", atTo.encode()), "cursor");
        assertViolations(params("from", FROM, "to", "2026-10-02T00:00:00Z", "cursor", "%%%"), "cursor");
    }

    @Test
    void theCursorRoundTripsAndRejectsForgedShapes() {
        AuditCursor cursor = new AuditCursor(Instant.parse("2026-10-01T10:00:00.123456Z"), UUID.randomUUID());

        assertThat(AuditCursor.decode(cursor.encode())).isEqualTo(cursor);
        for (String json : List.of("[]", "{\"o\":\"x\"}", "{\"o\":1,\"e\":2}", "{\"o\":\"bad\",\"e\":\"bad\"}")) {
            String forged = Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> AuditCursor.decode(forged))
                    .as(json)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields())
                            .extracting(FieldViolation::field)
                            .containsExactly("cursor"));
        }
    }

    @Test
    void aPageKeepsLimitItemsAndPointsTheCursorAtTheLastOne() {
        List<AuditEventRecord> rows = List.of(record(3), record(2), record(1));

        AuditEventPage full = AuditEventPage.of(rows, 2);
        AuditEventPage last = AuditEventPage.of(rows, 3);

        assertThat(full.items()).hasSize(2);
        assertThat(full.nextCursor()).contains(rows.get(1).position());
        assertThat(last.items()).hasSize(3);
        assertThat(last.nextCursor()).isEmpty();
    }

    private static AuditEventRecord record(int minute) {
        return new AuditEventRecord(
                UUID.randomUUID(),
                Optional.empty(),
                "system",
                com.memetrade.audit.api.ActorType.SYSTEM,
                "TEST_ACTION",
                null,
                "corr",
                Instant.parse(FROM).plusSeconds(60L * minute),
                Optional.empty(),
                null);
    }

    private static void assertViolations(Map<String, List<String>> parameters, String... fields) {
        assertThatThrownBy(() -> AuditEventQuery.fromParameters(parameters))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fields()).extracting(FieldViolation::field).containsExactlyInAnyOrder(fields);
                });
    }

    private static Map<String, List<String>> params(String... pairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], List.of(pairs[i + 1]));
        }
        return map;
    }
}
