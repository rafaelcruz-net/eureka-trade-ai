package com.memetrade.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 261121-paper-real-unico U2, Etapa 21 (infrastructure-specification.md "Conexões do banco", NFR1.16): the api checks
 * the size of the pool {@code pnl-mismatch-audit} at start-up, 1 to 2, with a message that names the property; the
 * worker never opens that pool and does not check it.
 */
class RuntimeSettingsGuardMismatchPoolTest {

    private static MockEnvironment api(String poolSize) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(RuntimeSettingsGuard.POOL_PROPERTY, "20")
                .withProperty(RuntimeSettingsGuard.SCHEDULER_PROPERTY, "23");
        if (poolSize != null) {
            environment.withProperty(RuntimeSettingsGuard.MISMATCH_AUDIT_POOL_PROPERTY, poolSize);
        }
        return environment;
    }

    @Test
    void theShippedSizeAndOneAreAccepted() {
        assertThatCode(() -> new RuntimeSettingsGuard(api("2"))).doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(api("1"))).doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(api(null))).doesNotThrowAnyException();
    }

    @Test
    void aSizeOutOfRangeStopsTheApiNamingTheProperty() {
        for (String wrong : new String[] {"0", "3", "two"}) {
            assertThatThrownBy(() -> new RuntimeSettingsGuard(api(wrong)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("memetrade.pnl.mismatch-audit.pool-size")
                    .hasMessageNotContaining(wrong + " ");
        }
    }

    @Test
    void theWorkerDoesNotCheckThePoolItNeverOpens() {
        MockEnvironment worker = new MockEnvironment()
                .withProperty(ProcessRole.PROPERTY, "worker")
                .withProperty(RuntimeSettingsGuard.POOL_PROPERTY, "36")
                .withProperty(RuntimeSettingsGuard.SCHEDULER_PROPERTY, "28")
                .withProperty(RuntimeSettingsGuard.MISMATCH_AUDIT_POOL_PROPERTY, "9");

        assertThatCode(() -> new RuntimeSettingsGuard(worker)).doesNotThrowAnyException();
    }
}
