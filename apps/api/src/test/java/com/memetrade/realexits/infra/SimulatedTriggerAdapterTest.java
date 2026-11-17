package com.memetrade.realexits.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.realexits.domain.ConditionalKind;
import com.memetrade.realexits.domain.TriggerPort;
import com.memetrade.realexits.domain.TriggerPort.CancelResult;
import com.memetrade.realexits.domain.TriggerPort.CreateResult;
import com.memetrade.realexits.domain.TriggerPort.ProviderStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;

/**
 * Step 6 of the U7, written BEFORE the code (BR0.3, BR9.1, infrastructure-specification.md "Caminho simulado"): the
 * simulated adapter keeps a record of its own, authorizes by the consent message, opens and cancels without any
 * transaction, never fills and never names a custody account; it exists only with the test profile and the adapter
 * {@code simulated}.
 */
@Tag("u7-exits-and-fiscal")
class SimulatedTriggerAdapterTest {

    private static final String MINT = "Mint1111111111111111111111111111111111111111";

    private final SimulatedTriggerAdapter adapter = new SimulatedTriggerAdapter();

    private static TriggerPort.CreateRequest request(UUID id) {
        return new TriggerPort.CreateRequest(
                id,
                "Bot11111111111111111111111111111111111111111",
                "Main1111111111111111111111111111111111111111",
                MINT,
                ConditionalKind.OCO,
                Optional.of(new BigDecimal("0.00008")),
                Optional.of(new BigDecimal("0.00015")),
                5_000L,
                200);
    }

    @Test
    void itIsTheSimulatedAdapterAuthorizedByTheConsentMessage() {
        assertThat(adapter.kind()).isEqualTo(AdapterKind.SIMULATED);
        assertThat(adapter.authorization()).isEqualTo(TriggerPort.Authorization.MESSAGE);
        assertThatThrownBy(() -> adapter.prepareCreate(request(UUID.randomUUID())))
                .as("there is no transaction to build")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void itOpensWithoutATransactionNorACustodyAccount() {
        UUID id = UUID.randomUUID();

        CreateResult result = adapter.create(request(id), "signature-of-the-consent");

        assertThat(result).isInstanceOfSatisfying(CreateResult.Opened.class, opened -> {
            assertThat(opened.providerOrderId()).startsWith("sim-");
            assertThat(opened.createSignature()).isEmpty();
            assertThat(opened.escrowAccount()).isEmpty();
        });
        assertThat(adapter.status(ref(id, ((CreateResult.Opened) result).providerOrderId())))
                .isInstanceOf(ProviderStatus.Open.class);
    }

    @Test
    void theCancellationIsConfirmedAtOnceWithoutASignature() {
        UUID id = UUID.randomUUID();
        String provider = ((CreateResult.Opened) adapter.create(request(id), "s")).providerOrderId();

        assertThat(adapter.cancel(ref(id, provider))).isEqualTo(new CancelResult.Cancelled(Optional.empty()));
    }

    /**
     * infrastructure-specification.md: the simulated path never fills nor expires and keeps no state (the api creates
     * and the worker cancels, in two processes): every order reads OPEN until the job cancels it.
     */
    @Test
    void itNeverFillsNorExpiresAndKeepsNoState() {
        assertThat(adapter.status(ref(UUID.randomUUID(), "sim-unknown"))).isInstanceOf(ProviderStatus.Open.class);
        assertThat(new SimulatedTriggerAdapter().status(ref(UUID.randomUUID(), "sim-other")))
                .isInstanceOf(ProviderStatus.Open.class);
    }

    @Test
    void theBeanExistsOnlyWithTheTestProfileAndTheSimulatedAdapter() {
        assertThat(SimulatedTriggerAdapter.class.getAnnotation(Profile.class).value())
                .containsExactly("test");
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(SimulatedTriggerAdapter.class);

        runner.withPropertyValues("spring.profiles.active=production", "memetrade.real.exits.adapter=simulated")
                .run(context -> assertThat(context).doesNotHaveBean(SimulatedTriggerAdapter.class));
        runner.withPropertyValues("spring.profiles.active=test", "memetrade.real.exits.adapter=jupiter")
                .run(context -> assertThat(context).doesNotHaveBean(SimulatedTriggerAdapter.class));
        runner.withPropertyValues("spring.profiles.active=test", "memetrade.real.exits.adapter=simulated")
                .run(context -> assertThat(context).hasSingleBean(SimulatedTriggerAdapter.class));
    }

    private static TriggerPort.OrderRef ref(UUID id, String provider) {
        return new TriggerPort.OrderRef(id, "Bot", MINT, Optional.of(provider), Optional.empty());
    }
}
