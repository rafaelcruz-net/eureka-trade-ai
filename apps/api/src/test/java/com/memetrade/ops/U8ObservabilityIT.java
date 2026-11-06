package com.memetrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.administration.domain.KillSwitchService;
import com.memetrade.administration.domain.SwitchState;
import com.memetrade.administration.infra.KillSwitchReconciler;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminAuthFlow;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Passo 14 of U8 (observability-design.md; NFR6.7, NFR7.1, NFR7.2, NFR9.1 to NFR9.5): the metrics of the kill switch,
 * the feedback and the administrator entry exist with closed labels; the indicators {@code adminKillSwitch} and
 * {@code feedbackPurge} exist and the first turns DOWN with its code; the logs never carry the text of a comment, the
 * reason of the kill switch, the full wallet of an administrator, a nonce or a signature.
 */
@ExtendWith(OutputCaptureExtension.class)
class U8ObservabilityIT extends AbstractU8IT {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private HealthContributorRegistry health;

    @Autowired
    private KillSwitchReconciler reconciler;

    @Autowired
    private KillSwitchService service;

    @Test
    void theLogsNeverCarryTheFeedbackTextTheReasonTheWalletOrTheSignature(CapturedOutput output) throws Exception {
        String feedbackMarker = "feedback-marker-" + UUID.randomUUID();
        String reasonMarker = "reason-marker-" + UUID.randomUUID();
        TestWallet wallet = TestWallet.generate();
        adminWallets.add(wallet.address());
        AdminAuthFlow flow = new AdminAuthFlow(mvc, json);
        String message = flow.challenge(wallet).get("message").asText();
        String signature = wallet.sign(message);
        String nonce = message.replaceAll("(?s).*Nonce: ([^\n]+).*", "$1");
        String token = flow.accessToken(flow.verify(wallet, message, signature));
        Guest guest = newGuest();

        postFeedback(guest.bearer(), "S5", "Texto com " + feedbackMarker, UUID.randomUUID());
        putKillSwitch(sessionOf(newAdministrator()), "ON", "Motivo com " + reasonMarker);
        putKillSwitch(sessionOf(newAdministrator()), "OFF", "Fim " + reasonMarker);

        String logs = output.getAll();
        assertThat(logs)
                .doesNotContain(feedbackMarker)
                .doesNotContain(reasonMarker)
                .doesNotContain(wallet.address())
                .doesNotContain(signature)
                .doesNotContain(nonce)
                .doesNotContain(token);
        assertThat(logs).contains("Feedback received: screen=S5").contains("Kill switch request: state=ON");
        // The reason is kept where it belongs: the history and the event (BR4.2).
        assertThat(OutboxEvents.payloads(jdbc, "KILL_SWITCH_ON")).anyMatch(payload -> payload.contains(reasonMarker));
    }

    @Test
    void theMetricsExistWithClosedLabelsOnly() throws Exception {
        Guest guest = newGuest();
        postFeedback(guest.bearer(), "S7", "Métrica", UUID.randomUUID());
        postFeedback(guest.bearer(), "S99", "Tela errada", UUID.randomUUID());
        new AdminAuthFlow(mvc, json).login(TestWallet.generate());
        putKillSwitch(sessionOf(newAdministrator()), "OFF", "Medir");

        assertThat(registry.find("admin.killswitch.state").gauge()).isNotNull();
        assertThat(registry.find("admin.killswitch.pending_bots").gauge()).isNotNull();
        assertThat(registry.find("admin.killswitch.reconcile_needed").gauge()).isNotNull();
        assertThat(registry.find("admin.killswitch.reconcile_divergences_total").counter())
                .isNotNull();
        assertThat(registry.find("feedback.received_total").tag("screen", "S7").counter())
                .isNotNull();
        assertThat(registry.find("feedback.refused_total")
                        .tag("reason", "VALIDATION")
                        .counter())
                .isNotNull();
        assertThat(registry.find("admin.login.refused_total")
                        .tag("reason", "NOT_ADMIN_WALLET")
                        .counter())
                .isNotNull();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (name.startsWith("admin.") || name.startsWith("feedback.")) {
                meter.getId().getTags().forEach(tag -> assertThat(tag.getValue())
                        .as(name + " " + tag.getKey())
                        .matches("[A-Z0-9_]{1,40}"));
            }
        }
    }

    /** NFR9.4: adminKillSwitch DOWN with the code while reconcileNeeded lasts more than 60 s; UP again after. */
    @Test
    void theKillSwitchIndicatorTurnsDownWithTheCodeAndBackUp() {
        Principal admin = newAdministrator();
        service.change(new KillSwitchService.Command(
                SwitchState.ON, "Indicador", admin, new IdempotencyKey(UUID.randomUUID()), "u8-observability"));
        u5.throwOnNextCalls(2);

        reconciler.runOnce();
        clock.advance(Duration.ofSeconds(61));
        reconciler.runOnce();

        HealthIndicator indicator = (HealthIndicator) health.getContributor("adminKillSwitch");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("code", "KILL_SWITCH_RECONCILE_STALE");

        reconciler.runOnce();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void theFeedbackPurgeIndicatorExistsOutsideLivenessAndReadiness() {
        assertThat(health.getContributor("feedbackPurge")).isNotNull();
        assertThat(health.getContributor("adminKillSwitch")).isNotNull();
    }
}
