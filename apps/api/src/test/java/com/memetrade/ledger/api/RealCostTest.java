package com.memetrade.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 8 (written before the code; F-3, NFR1.11, team.md "unknown como tipo explícito"):
 * the cost of a REAL position is {@code Known(lamports)}, never negative, or {@code Unknown(reason)}. Nothing turns an
 * unknown cost into 0: the type offers no number for it, and every reader handles both cases.
 */
class RealCostTest {

    @Test
    void aKnownCostIsNeverNegative() {
        assertThat(new RealCost.Known(0).lamports()).isZero();
        assertThat(new RealCost.Known(52_047_737L).lamports()).isEqualTo(52_047_737L);
        assertThatThrownBy(() -> new RealCost.Known(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownCostCarriesOneOfTheFiveReasons() {
        assertThat(RealCostUnknownReason.values())
                .containsExactlyInAnyOrder(
                        RealCostUnknownReason.NOT_READ,
                        RealCostUnknownReason.TIMEOUT,
                        RealCostUnknownReason.ERROR,
                        RealCostUnknownReason.UNKNOWN_BOT,
                        RealCostUnknownReason.INCONSISTENT_READ);
        for (RealCostUnknownReason reason : RealCostUnknownReason.values()) {
            assertThat(new RealCost.Unknown(reason).reason()).isEqualTo(reason);
        }
        assertThatThrownBy(() -> new RealCost.Unknown(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nothingTurnsAnUnknownCostIntoANumber() {
        Set<Class<?>> numbers = Set.of(long.class, Long.class, int.class, Integer.class, java.math.BigInteger.class);
        for (Class<?> type : new Class<?>[] {RealCost.class, RealCost.Unknown.class}) {
            assertThat(Arrays.stream(type.getMethods())
                            .filter(m -> m.getDeclaringClass() != Object.class)
                            .filter(m -> !m.getName().equals("hashCode"))
                            .map(Method::getReturnType))
                    .as(type.getSimpleName())
                    .noneMatch(numbers::contains);
        }
    }

    @Test
    void everyReaderHandlesBothCases() {
        assertThat(describe(new RealCost.Known(10))).isEqualTo("known 10");
        assertThat(describe(new RealCost.Unknown(RealCostUnknownReason.TIMEOUT)))
                .isEqualTo("unknown TIMEOUT");
        assertThat(RealCost.class.isSealed()).isTrue();
        assertThat(RealCost.class.getPermittedSubclasses()).hasSize(2);
    }

    private static String describe(RealCost cost) {
        return switch (cost) {
            case RealCost.Known known -> "known " + known.lamports();
            case RealCost.Unknown unknown -> "unknown " + unknown.reason();
        };
    }
}
