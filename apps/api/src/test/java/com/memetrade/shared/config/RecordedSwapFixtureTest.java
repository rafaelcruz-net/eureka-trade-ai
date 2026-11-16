package com.memetrade.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 261113-trading-real-bot U4: the recorded swap of the local validator exists only in the test profile with
 * {@code RECORDED_MINT} set, and refuses values that would make the e2e prove nothing (test-after: configuration).
 */
class RecordedSwapFixtureTest {

    private static final String MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(RecordedSwapFixtureConfig.class)
            .withPropertyValues(
                    "memetrade.real.test-swap.decimals=5",
                    "memetrade.real.test-swap.buy-lamports=50000000",
                    "memetrade.real.test-swap.expected-out-units=154230000000");

    @Test
    void theRecordedValuesGiveThePriceOfTheRecordedQuote() {
        RecordedSwapFixture fixture = new RecordedSwapFixture(MINT, 5, 50_000_000L, 154_230_000_000L);

        assertThat(fixture.expectedOutTokens()).isEqualByComparingTo("1542300");
        // 0.05 SOL x 72.558 USD = 3.6279 USD for 1,542,300 tokens, rounded down at 12 places.
        assertThat(fixture.tokenUsd()).isEqualByComparingTo(new BigDecimal("0.000002352266"));
        assertThat(fixture.isRecordedMint(MINT)).isTrue();
        assertThat(fixture.isRecordedMint(RecordedSwapFixture.WRAPPED_SOL)).isFalse();
    }

    @Test
    void anInvalidRecordingStopsTheStartNamingTheVariable() {
        assertThatThrownBy(() -> new RecordedSwapFixture("not-a-mint", 5, 1, 1)).hasMessageContaining("RECORDED_MINT");
        assertThatThrownBy(() -> new RecordedSwapFixture(RecordedSwapFixture.WRAPPED_SOL, 9, 1, 1))
                .hasMessageContaining("RECORDED_MINT");
        assertThatThrownBy(() -> new RecordedSwapFixture(MINT, 19, 1, 1)).hasMessageContaining("RECORDED_DECIMALS");
        assertThatThrownBy(() -> new RecordedSwapFixture(MINT, 5, 0, 1)).hasMessageContaining("RECORDED_BUY_LAMPORTS");
        assertThatThrownBy(() -> new RecordedSwapFixture(MINT, 5, 1_000_000_001L, 1))
                .hasMessageContaining("RECORDED_BUY_LAMPORTS");
        assertThatThrownBy(() -> new RecordedSwapFixture(MINT, 5, 1, 0)).hasMessageContaining("RECORDED_SELL_UNITS");
    }

    @Test
    void theTestProfileWithTheMintCreatesTheFixture() {
        runner.withPropertyValues("spring.profiles.active=test", "memetrade.real.test-swap.mint=" + MINT)
                .run(context -> assertThat(
                                context.getBean(RecordedSwapFixture.class).buyLamports())
                        .isEqualTo(50_000_000L));
    }

    @Test
    void withoutTheRecordingsTheTestProfileHasNoFixture() {
        runner.withPropertyValues("spring.profiles.active=test", "memetrade.real.test-swap.mint=")
                .run(context -> assertThat(context).doesNotHaveBean(RecordedSwapFixture.class));
    }

    @Test
    void noOtherProfileEverCreatesTheFixture() {
        runner.withPropertyValues("spring.profiles.active=local", "memetrade.real.test-swap.mint=" + MINT)
                .run(context -> assertThat(context).doesNotHaveBean(RecordedSwapFixture.class));
        runner.withPropertyValues("memetrade.real.test-swap.mint=" + MINT)
                .run(context -> assertThat(context).doesNotHaveBean(RecordedSwapFixture.class));
    }

    @Test
    void aPriceThatRoundsToZeroStopsTheStart() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "memetrade.real.test-swap.mint=" + MINT,
                        "memetrade.real.test-swap.decimals=0",
                        "memetrade.real.test-swap.buy-lamports=1",
                        "memetrade.real.test-swap.expected-out-units=9000000000000000000")
                .run(context -> assertThat(context).hasFailed());
    }
}
