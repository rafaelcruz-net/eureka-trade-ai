package com.memetrade.realexits.infra;

import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.realexits.domain.TriggerPort;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The simulated Jupiter Trigger API (BR0.3, BR9.1, infrastructure-specification.md "Caminho simulado"): it proves the
 * domain, the routes, the screens and the cancellation without the real Jupiter. The "order" is only the row of the
 * conditional itself: the adapter keeps no state (the api creates and the worker cancels, in two processes), builds no
 * transaction, never calls the TxSigner nor the network, never fills and names no custody account (the token stays in
 * the bot wallet, so the reconciliation stays clean). The person authorizes with the signed consent of the conditional
 * ({@code signMessage}), the form BR1.2 foresees when the Jupiter refuses the co-signature. A simulated order reads
 * OPEN until its cancellation, which is confirmed at once, in the next run of the cancellation job.
 *
 * <p>Guards: the bean exists only with the {@code test} profile and {@code memetrade.real.exits.adapter=simulated};
 * {@code RealProperties} refuses that adapter outside the LOCALNET; an ArchUnit rule forbids any production class to
 * reference it; there is no test-only route (the e2e uses the routes R5).
 */
@Component
@Profile("test")
@ConditionalOnProperty(name = "memetrade.real.exits.adapter", havingValue = "simulated")
public class SimulatedTriggerAdapter implements TriggerPort {

    @Override
    public AdapterKind kind() {
        return AdapterKind.SIMULATED;
    }

    @Override
    public Authorization authorization() {
        return Authorization.MESSAGE;
    }

    @Override
    public String prepareCreate(CreateRequest request) {
        throw new UnsupportedOperationException("The simulated adapter builds no transaction");
    }

    @Override
    public CreateResult create(CreateRequest request, String authorization) {
        return new CreateResult.Opened("sim-" + request.conditionalOrderId(), Optional.empty(), Optional.empty());
    }

    @Override
    public CancelResult cancel(OrderRef order) {
        return new CancelResult.Cancelled(Optional.empty());
    }

    /** Never FILLED nor EXPIRED: a simulated order stays open until the cancellation job cancels it. */
    @Override
    public ProviderStatus status(OrderRef order) {
        return new ProviderStatus.Open();
    }
}
