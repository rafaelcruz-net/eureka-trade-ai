package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.web.QueryParameters;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 261121-paper-real-unico U3, Etapa 4 (test after the layer; BR2.4, AC7.2.3, NFR4.7): the window of C4 and C6 is read
 * from {@code period}, {@code from} and {@code to} without the database; every refusal names its field.
 */
class PnlWindowsTest {

    private static final Instant FROM = Instant.parse("2026-11-01T03:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-08T03:00:00Z");

    @Test
    void withoutAPeriodTheWindowIsAll() {
        assertThat(read(params())).contains(PnlWindow.all());
        assertThat(read(params("period", "ALL"))).contains(PnlWindow.all());
    }

    @Test
    void aRangeWithFromAndToIsTheHalfOpenWindow() {
        assertThat(read(params("period", "RANGE", "from", FROM.toString(), "to", TO.toString())))
                .contains(new PnlWindow.Range(FROM, TO));
    }

    @Test
    void aRangeWithoutFromOrToIsRefusedOnTheMissingField() {
        assertRefused(params("period", "RANGE", "to", TO.toString()), "from");
        assertRefused(params("period", "RANGE", "from", FROM.toString()), "to");
    }

    @Test
    void fromAtOrAfterToIsRefused() {
        assertRefused(params("period", "RANGE", "from", TO.toString(), "to", FROM.toString()), "from");
        assertRefused(params("period", "RANGE", "from", FROM.toString(), "to", FROM.toString()), "from");
    }

    @Test
    void aRangeOfMoreThan366DaysIsRefusedAndExactly366IsAccepted() {
        Instant limit = FROM.plusSeconds(366L * 86_400);
        assertThat(read(params("period", "RANGE", "from", FROM.toString(), "to", limit.toString())))
                .contains(new PnlWindow.Range(FROM, limit));
        assertRefused(
                params(
                        "period",
                        "RANGE",
                        "from",
                        FROM.toString(),
                        "to",
                        limit.plusSeconds(1).toString()),
                "to");
    }

    @Test
    void fromOrToWithAllAndAnotherPeriodAreRefused() {
        assertRefused(params("period", "ALL", "from", FROM.toString()), "from");
        assertRefused(params("to", TO.toString()), "to");
        assertRefused(params("period", "WEEK"), "period");
        assertRefused(params("period", "RANGE", "from", "2026-11-01", "to", TO.toString()), "from");
    }

    @Test
    void theAnswerOfTheWindowCarriesItsKindAndInstants() {
        assertThat(PnlWindows.dto(PnlWindow.all())).isEqualTo(new ModePnlDtos.PnlPeriodDto("ALL", null, null));
        assertThat(PnlWindows.dto(new PnlWindow.Range(FROM, TO)))
                .isEqualTo(new ModePnlDtos.PnlPeriodDto("RANGE", FROM, TO));
    }

    private static Optional<PnlWindow> read(MultiValueMap<String, String> values) {
        QueryParameters query = QueryParameters.of(values, Set.of("period", "from", "to"));
        Optional<PnlWindow> window = PnlWindows.read(query);
        query.validate();
        return window;
    }

    private static void assertRefused(MultiValueMap<String, String> values, String field) {
        assertThatThrownBy(() -> read(values)).isInstanceOf(ApiException.class).satisfies(failure -> assertThat(
                        ((ApiException) failure).fields())
                .anySatisfy(violation -> assertThat(violation.field()).isEqualTo(field)));
    }

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.add(pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
