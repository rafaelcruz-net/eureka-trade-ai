package com.memetrade.riskassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.web.TestRequestSessions;
import com.memetrade.riskassessment.api.RiskWeightAdministration;
import com.memetrade.riskassessment.api.RiskWeightAdministration.ActivationResult;
import com.memetrade.riskassessment.api.WeightSetDraft;
import com.memetrade.riskassessment.api.WeightSetStatus;
import com.memetrade.riskassessment.api.WeightSetView;
import com.memetrade.riskassessment.domain.WeightsV1;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

/** F7 through the public interface used by U8 (Step 9): administrator only, validation, activation and audit. */
@Tag("u4")
class RiskWeightAdministrationIT extends AbstractPostgresIT {

    private static final Principal ADMIN = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
    private static final Principal GUEST = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

    @Autowired
    private RiskWeightAdministration administration;

    @Test
    void anAdministratorCreatesADraftAsTheNextVersion() {
        WeightSetView created = asAdmin(() -> administration.create(WeightsV1.draft(), request()));

        assertThat(created.status()).isEqualTo(WeightSetStatus.DRAFT);
        assertThat(created.version()).isGreaterThan(1);
        assertThat(created.createdBy()).isEqualTo(ADMIN.userId().toString());
        assertThat(created.definition().minCoverage()).isEqualByComparingTo("80.0000");
    }

    @Test
    void aGuestIsRefusedWith403AndExactlyOneRequestForbiddenAndNothingChanges() {
        int before = versions();
        String correlation = CorrelationId.withNew(() -> {
            assertThatThrownBy(() ->
                            TestRequestSessions.as(GUEST, () -> administration.create(WeightsV1.draft(), request())))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                            .isEqualTo(ErrorCode.FORBIDDEN_ROLE));
            return CorrelationId.current();
        });

        assertThat(actions(correlation)).containsExactly("REQUEST_FORBIDDEN");
        assertThat(versions()).isEqualTo(before);
    }

    @Test
    void anInvalidDraftIs400WithTheFieldsAndNothingIsWritten() {
        int before = versions();
        WeightSetDraft invalid = new WeightSetDraft(WeightsV1.draft().factors(), 69, 39, new BigDecimal("0"));

        assertThatThrownBy(() -> asAdmin(() -> administration.create(invalid, request())))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refused.fields()).extracting(f -> f.field()).contains("lowMax", "minCoverage");
                });
        assertThat(versions()).isEqualTo(before);
    }

    @Test
    void activatingADraftRetiresTheActiveOneAndPublishesExactlyOneEvent() {
        int draft = asAdmin(() -> administration.create(WeightsV1.draft(), request()))
                .version();
        int previous = activeVersion();

        String correlation = CorrelationId.withNew(() -> {
            ActivationResult result = asAdmin(() -> administration.activate(draft, request()));
            assertThat(result.changed()).isTrue();
            assertThat(result.retiredVersion()).contains(previous);
            return CorrelationId.current();
        });

        assertThat(activeVersion()).isEqualTo(draft);
        assertThat(status(previous)).isEqualTo("RETIRED");
        assertThat(actions(correlation)).containsExactly("RISK_WEIGHTS_ACTIVATED");
        assertThat(jdbc.sql("select payload->'details'->>'fromVersion' || '>' || (payload->'details'->>'toVersion')"
                                + " from audit_outbox where payload->>'correlationId' = :c")
                        .param("c", correlation)
                        .query(String.class)
                        .single())
                .isEqualTo(previous + ">" + draft);
    }

    @Test
    void activatingTheActiveVersionAgainChangesNothingAndPublishesNothing() {
        int active = activeVersion();

        String correlation = CorrelationId.withNew(() -> {
            assertThat(asAdmin(() -> administration.activate(active, request())).changed())
                    .isFalse();
            return CorrelationId.current();
        });

        assertThat(actions(correlation)).isEmpty();
        assertThat(activeVersion()).isEqualTo(active);
    }

    @Test
    void anActivatedOrRetiredVersionNeverChangesAndARetiredOneIsNotActivatedAgain() {
        int first = asAdmin(() -> administration.create(WeightsV1.draft(), request()))
                .version();
        asAdmin(() -> administration.activate(first, request()));
        int second = asAdmin(() -> administration.create(WeightsV1.draft(), request()))
                .version();
        asAdmin(() -> administration.activate(second, request()));

        assertNotEditable(() -> asAdmin(() -> administration.update(second, WeightsV1.draft(), request())));
        assertNotEditable(() -> asAdmin(() -> administration.update(first, WeightsV1.draft(), request())));
        assertNotEditable(() -> asAdmin(() -> administration.activate(first, request())));
    }

    @Test
    void aDraftCanBeChangedBeforeItsActivation() {
        int draft = asAdmin(() -> administration.create(WeightsV1.draft(), request()))
                .version();
        WeightSetDraft stricter = new WeightSetDraft(WeightsV1.draft().factors(), 30, 60, new BigDecimal("90.0000"));

        WeightSetView changed = asAdmin(() -> administration.update(draft, stricter, request()));

        assertThat(changed.definition().lowMax()).isEqualTo(30);
        assertThat(asAdmin(() -> administration.list(request())))
                .extracting(WeightSetView::version)
                .contains(draft);
    }

    @Test
    void anUnknownVersionIsNotFound() {
        assertThatThrownBy(() -> asAdmin(() -> administration.activate(999_999, request())))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void twoSimultaneousActivationsRunOneAfterTheOtherAndLeaveOneActiveVersion() throws Exception {
        int a = asAdmin(() -> administration.create(WeightsV1.draft(), request()))
                .version();
        int b = asAdmin(() -> administration.create(WeightsV1.draft(), request()))
                .version();

        List<CompletableFuture<ActivationResult>> runs = new ArrayList<>();
        for (int version : List.of(a, b)) {
            runs.add(CompletableFuture.supplyAsync(() -> asAdmin(() -> administration.activate(version, request()))));
        }
        for (CompletableFuture<ActivationResult> run : runs) {
            assertThat(run.get().changed()).isTrue();
        }

        assertThat(jdbc.sql("select count(*) from risk_weight_set where status = 'ACTIVE'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(List.of(status(a), status(b))).containsExactlyInAnyOrder("ACTIVE", "RETIRED");
    }

    private static <T> T asAdmin(Supplier<T> work) {
        return TestRequestSessions.as(ADMIN, work);
    }

    private static void assertNotEditable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                .isEqualTo(ErrorCode.WEIGHT_SET_NOT_EDITABLE));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/admin/risk-weights");
    }

    private int versions() {
        return jdbc.sql("select count(*) from risk_weight_set")
                .query(Integer.class)
                .single();
    }

    private int activeVersion() {
        return jdbc.sql("select version from risk_weight_set where status = 'ACTIVE'")
                .query(Integer.class)
                .single();
    }

    private String status(int version) {
        return jdbc.sql("select status from risk_weight_set where version = :v")
                .param("v", version)
                .query(String.class)
                .single();
    }

    private List<String> actions(String correlation) {
        return jdbc.sql("select payload->>'action' from audit_outbox where payload->>'correlationId' = :c")
                .param("c", correlation)
                .query(String.class)
                .list();
    }
}
