package com.memetrade.bots.infra;

import com.memetrade.bots.domain.RealIneligibilityReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (NFR3.10; observability-design.md "Contador 1"): {@code bots.real.create.refusals}, one
 * series per strongest reason, registered with zero at start-up (5 series). Labels carry only enum values, never a
 * tenant, person, bot, wallet nor correlation id. Not bound to a process role, like {@code RealOpsMetrics}: the
 * {@code BotCommandService} that counts exists in the api and in the worker, where the series stay at zero.
 */
@Component
public class BotCreationMetrics {

    public static final String REFUSALS = "bots.real.create.refusals";

    private final Map<RealIneligibilityReason, Counter> refusals = new EnumMap<>(RealIneligibilityReason.class);

    public BotCreationMetrics(MeterRegistry meters) {
        for (RealIneligibilityReason reason : RealIneligibilityReason.values()) {
            refusals.put(
                    reason,
                    Counter.builder(REFUSALS)
                            .description(
                                    "REAL bot creations refused with 409 REAL_NOT_ELIGIBLE, by strongest reason (NFR3.10)")
                            .tag("reason", reason.name())
                            .register(meters));
        }
    }

    /** One 409 REAL_NOT_ELIGIBLE answered, after its audit event was written. */
    public void refused(RealIneligibilityReason reason) {
        refusals.get(reason).increment();
    }
}
