package com.memetrade.bots.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.infra.RealProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 261121-paper-real-unico U4, Etapa 5 (written before the code; BR5.1, NFR3.6, NFR8.7): the flag of the own quantity is
 * OFF by default in the api and in the worker, bound from {@code application.yml} as the processes bind it; only an
 * explicit {@code MEMETRADE_REAL_OWN_QUANTITY_SELL=true} turns it on. The {@link RealProperties} of the same prefix keeps
 * its own values (two records bound to {@code memetrade.real}).
 */
class OwnQuantitySellDefaultIT {

    @Test
    void withoutTheVariableTheFlagIsOffInTheApiAndInTheWorker() throws IOException {
        assertThat(flag(environment(false, Map.of())).enabled()).isFalse();
        assertThat(flag(environment(true, Map.of())).enabled()).isFalse();
    }

    @Test
    void theVariableTrueTurnsItOnInBothProcesses() throws IOException {
        Map<String, Object> on = Map.of("MEMETRADE_REAL_OWN_QUANTITY_SELL", "true");

        assertThat(flag(environment(false, on)).enabled()).isTrue();
        assertThat(flag(environment(true, on)).enabled()).isTrue();
    }

    @Test
    void theRealPropertiesOfTheSamePrefixKeepTheirValues() throws IOException {
        StandardEnvironment environment = environment(true, Map.of("MEMETRADE_REAL_OWN_QUANTITY_SELL", "true"));

        RealProperties real = Binder.get(environment)
                .bind("memetrade.real", RealProperties.class)
                .get();

        assertThat(real.enabled()).isFalse();
        assertThat(real.feeReserveLamports()).isEqualTo(30_000_000L);
        assertThat(flag(environment).enabled()).isTrue();
    }

    @Test
    void anyOtherValueThanTrueKeepsItOff() throws IOException {
        assertThat(flag(environment(false, Map.of("MEMETRADE_REAL_OWN_QUANTITY_SELL", "false")))
                        .enabled())
                .isFalse();
    }

    private static OwnQuantitySellProperties flag(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("memetrade.real", OwnQuantitySellProperties.class)
                .orElseGet(() -> new OwnQuantitySellProperties(false));
    }

    private static StandardEnvironment environment(boolean worker, Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        for (PropertySource<?> source : yaml("application.yml")) {
            environment.getPropertySources().addLast(source);
        }
        if (worker) {
            for (PropertySource<?> source : yaml("application-worker.yml")) {
                environment.getPropertySources().addAfter("variables", source);
            }
        }
        return environment;
    }

    private static List<PropertySource<?>> yaml(String name) throws IOException {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
    }
}
