package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.audit.api.RealReferences;
import com.memetrade.shared.logging.LogMasking;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 14, written before the code (BR6.1, BR6.2, AC8.5.1, AC8.5.2, NFR1.10): the transaction signature, the mint and
 * the bot wallet are accepted only in their typed fields; in the free details they stay refused; the main wallet goes
 * only as HMAC; logs mask a transaction signature like an address.
 */
class RealAuditValidatorTest {

    private static final Random SEEDED = new Random(5);
    private final AuditEventValidator validator = new AuditEventValidator();

    private static String signature() {
        byte[] bytes = new byte[64];
        SEEDED.nextBytes(bytes);
        bytes[0] = (byte) (bytes[0] | 1);
        return SyntheticIds.encode(bytes);
    }

    private static AuditEventCommand withdrawal(Map<String, String> details, RealReferences real) {
        return AuditEventCommand.person(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "REAL_WITHDRAWAL_CONFIRMED",
                        "withdrawal:" + UUID.randomUUID(),
                        "corr-1",
                        Instant.parse("2026-11-14T12:00:00Z"),
                        details)
                .withReal(real);
    }

    @Test
    void theTypedFieldsAcceptASignatureAMintAndTheBotWallet() {
        RealReferences real = new RealReferences(
                signature(),
                TestWallet.generate().address(),
                TestWallet.generate().address());

        assertThatCode(() -> validator.validate(withdrawal(Map.of("lamports", "5"), real)))
                .doesNotThrowAnyException();
    }

    @Test
    void aTypedSignatureThatIsNotSixtyFourBytesIsRefused() {
        RealReferences shortSignature = new RealReferences(TestWallet.generate().address(), null, null);

        assertThatThrownBy(() -> validator.validate(withdrawal(Map.of(), shortSignature)))
                .isInstanceOf(AuditPublishRejectedException.class)
                .hasMessageContaining("real.txSignature");
    }

    @Test
    void aTypedAddressThatIsNotAWalletIsRefused() {
        assertThatThrownBy(() ->
                        validator.validate(withdrawal(Map.of(), new RealReferences(null, null, "not-an-address"))))
                .isInstanceOf(AuditPublishRejectedException.class)
                .hasMessageContaining("real.botWalletAddress");
        assertThatThrownBy(() -> validator.validate(withdrawal(Map.of(), new RealReferences(null, "0OIl", null))))
                .isInstanceOf(AuditPublishRejectedException.class)
                .hasMessageContaining("real.mint");
    }

    @Test
    void theSameValuesInTheFreeDetailsAreStillRefused() {
        assertThatThrownBy(() -> validator.validate(withdrawal(Map.of("tx", signature()), null)))
                .isInstanceOf(AuditPublishRejectedException.class);
        assertThatThrownBy(() -> validator.validate(
                        withdrawal(Map.of("wallet", TestWallet.generate().address()), null)))
                .isInstanceOf(AuditPublishRejectedException.class);
    }

    @Test
    void aSignatureInTheTargetRefIsStillRefused() {
        AuditEventCommand command = AuditEventCommand.person(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REAL_DEPOSIT_RECORDED",
                "deposit:" + signature(),
                "corr-2",
                Instant.parse("2026-11-14T12:00:00Z"),
                Map.of());

        assertThatThrownBy(() -> validator.validate(command)).isInstanceOf(AuditPublishRejectedException.class);
    }

    @Test
    void theMainWalletTravelsOnlyInTheWalletFieldThatBecomesAnHmac() {
        String main = TestWallet.generate().address();

        AuditEventCommand command = withdrawal(Map.of(), null).withWallet(main);

        assertThat(command.wallet()).isEqualTo(main);
        assertThat(command.toString()).doesNotContain(main);
        assertThatCode(() -> validator.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void withRealKeepsEveryOtherFieldAndTheWallet() {
        String main = TestWallet.generate().address();
        RealReferences real =
                new RealReferences(null, null, TestWallet.generate().address());

        AuditEventCommand command =
                withdrawal(Map.of("k", "v"), null).withWallet(main).withReal(real);

        assertThat(command.real()).isEqualTo(real);
        assertThat(command.wallet()).isEqualTo(main);
        assertThat(command.details()).containsEntry("k", "v");
    }

    @Test
    void logsMaskATransactionSignatureAsWellAsAnAddress() {
        String signature = signature();
        String address = TestWallet.generate().address();

        String masked = LogMasking.maskBase58("sent " + signature + " from " + address);

        assertThat(masked).doesNotContain(signature).doesNotContain(address);
        assertThat(masked).contains(signature.substring(0, 4)).contains(address.substring(0, 4));
    }
}
