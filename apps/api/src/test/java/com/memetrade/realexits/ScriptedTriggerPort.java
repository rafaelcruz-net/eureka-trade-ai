package com.memetrade.realexits;

import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.realexits.domain.TriggerPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Jupiter Trigger API of the integration tests (X1), scripted per conditional: what {@link #status} answers, what
 * {@link #cancel} answers, whether the provider is down (U7-NFR4.6) and how many calls reached it (the guard never
 * calls it, logical-components.md). It plays a JUPITER adapter with a recorded custody account, so the reconciliation
 * and the fills see a real-like order; the simulated adapter is tested on its own.
 */
public class ScriptedTriggerPort implements TriggerPort {

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicBoolean down = new AtomicBoolean();
    public final AtomicReference<CreateResult> nextCreate = new AtomicReference<>();
    public final AtomicReference<AdapterKind> kind = new AtomicReference<>(AdapterKind.JUPITER);
    public final AtomicReference<Authorization> authorization = new AtomicReference<>(Authorization.MESSAGE);
    public final Map<UUID, ProviderStatus> statuses = new ConcurrentHashMap<>();
    public final Map<UUID, CancelResult> cancels = new ConcurrentHashMap<>();

    public void reset() {
        calls.set(0);
        down.set(false);
        nextCreate.set(null);
        kind.set(AdapterKind.JUPITER);
        authorization.set(Authorization.MESSAGE);
        statuses.clear();
        cancels.clear();
    }

    @Override
    public AdapterKind kind() {
        return kind.get();
    }

    @Override
    public Authorization authorization() {
        return authorization.get();
    }

    @Override
    public String prepareCreate(CreateRequest request) {
        hit();
        return "AQID";
    }

    @Override
    public CreateResult create(CreateRequest request, String authorizationValue) {
        hit();
        CreateResult scripted = nextCreate.get();
        if (scripted != null) {
            return scripted;
        }
        statuses.put(request.conditionalOrderId(), new ProviderStatus.Open());
        return new CreateResult.Opened(
                "jup-" + request.conditionalOrderId(),
                Optional.empty(),
                Optional.of(com.memetrade.testsupport.SyntheticIds.mint()));
    }

    @Override
    public CancelResult cancel(OrderRef order) {
        hit();
        CancelResult scripted = cancels.get(order.conditionalOrderId());
        if (scripted != null) {
            return scripted;
        }
        statuses.put(order.conditionalOrderId(), new ProviderStatus.Cancelled(Optional.empty()));
        return new CancelResult.Cancelled(Optional.empty());
    }

    @Override
    public ProviderStatus status(OrderRef order) {
        hit();
        return statuses.getOrDefault(order.conditionalOrderId(), new ProviderStatus.Open());
    }

    private void hit() {
        calls.incrementAndGet();
        if (down.get()) {
            throw new TriggerUnavailable("scripted outage");
        }
    }
}
