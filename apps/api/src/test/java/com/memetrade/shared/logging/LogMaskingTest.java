package com.memetrade.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.SyntheticIds;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class LogMaskingTest {

    @Test
    void masksBase58RunsOf32To44Characters() {
        String address = SyntheticIds.mint();
        String text = "login rejected for " + address + " reason=EXPIRED";

        assertThat(LogMasking.maskBase58(text))
                .doesNotContain(address)
                .contains(address.substring(0, 4) + "…" + address.substring(address.length() - 4))
                .contains("reason=EXPIRED");
    }

    @Test
    void leavesShortValuesUuidsAndPlainTextUntouched() {
        String uuid = "6f1c1f1e-1b1a-4c1d-9e1f-1a2b3c4d5e6f";
        assertThat(LogMasking.maskBase58(uuid)).isEqualTo(uuid);
        assertThat(LogMasking.maskBase58("abc")).isEqualTo("abc");
        assertThat(LogMasking.maskBase58("a".repeat(31))).isEqualTo("a".repeat(31));
        assertThat(LogMasking.maskBase58(null)).isNull();
    }

    @Test
    void masksEveryAddressInTheSameText() {
        String first = SyntheticIds.mint();
        String second = SyntheticIds.mint();

        String masked = LogMasking.maskBase58(first + "," + second);

        assertThat(masked).doesNotContain(first).doesNotContain(second);
    }

    @Test
    void bothBarriersMaskTheAddressInTheJsonLogLine() {
        String address = SyntheticIds.mint();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(CONTEXT);
        MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
        decorator.addValueMasker(new Base58AddressValueMasker());
        decorator.start();
        encoder.setJsonGeneratorDecorator(decorator);
        encoder.start();

        LoggingEvent sourceBarrier = event("wallet " + WalletAddress.of(address));
        LoggingEvent outputBarrier = event("raw " + address);

        String first = new String(encoder.encode(sourceBarrier), StandardCharsets.UTF_8);
        String second = new String(encoder.encode(outputBarrier), StandardCharsets.UTF_8);

        assertThat(first).doesNotContain(address).contains(WalletAddress.mask(address));
        assertThat(second).doesNotContain(address).contains(WalletAddress.mask(address));
    }

    @Test
    void valueMaskerReturnsNullWhenThereIsNothingToMask() {
        assertThat(new Base58AddressValueMasker().mask(null, "plain text")).isNull();
        assertThat(new Base58AddressValueMasker().mask(null, 42)).isNull();
    }

    private static final LoggerContext CONTEXT = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

    private static LoggingEvent event(String message) {
        return new LoggingEvent(
                LogMaskingTest.class.getName(), CONTEXT.getLogger("test"), Level.INFO, message, null, null);
    }
}
