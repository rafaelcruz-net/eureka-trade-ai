package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.audit.api.AuditPublishRejectedException.Reason;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BR1.2 and BR1.4 at the publisher: format, coherence and forbidden content, reported without the value; u2-retention
 * BR2.3: no full wallet address in details or as the actor.
 */
@Tag("u2")
class AuditEventValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final AuditEventValidator validator = new AuditEventValidator();

    @Test
    void aValidEventWithoutAWalletAddressPasses() {
        assertThatCode(() -> validator.validate(anonymous(Map.of("reason", "EXPIRED", "provider", "PHANTOM"))))
                .doesNotThrowAnyException();
    }

    // ----- u2-retention Passo 5, written before the code (BR2.3, NFR1.2): no full wallet address in details. -----

    @Test
    void aFullWalletAddressInDetailsIsRefusedWithFullWalletAndWithoutTheValue() {
        String address = TestWallet.generate().address();

        assertRejected(
                () -> validator.validate(anonymous(Map.of("walletAddress", address, "reason", "EXPIRED"))),
                "details.walletAddress",
                Reason.FULL_WALLET);
        assertThatThrownBy(() -> validator.validate(anonymous(Map.of("walletAddress", address))))
                .message()
                .doesNotContain(address);
    }

    @Test
    void aFullWalletAddressInsideAListOrAMapTextIsRefused() {
        String address = TestWallet.generate().address();

        assertRejected(
                () -> validator.validateDetails(Map.of("wallets", "[EXPIRED, " + address + "]")),
                "details.wallets",
                Reason.FULL_WALLET);
        assertRejected(
                () -> validator.validateDetails(Map.of("context", "{wallet=" + address + ", attempt=2}")),
                "details.context",
                Reason.FULL_WALLET);
        assertRejected(
                () -> validator.validateDetails(Map.of("note", "\"" + address + "\"")),
                "details.note",
                Reason.FULL_WALLET);
    }

    @Test
    void anyThirtyTwoByteBase58ValueIsRefusedWhateverTheKey() {
        String publicKeyShaped = SyntheticIds.mint();

        assertThat(AuditEventValidator.decodedLength(publicKeyShaped)).isEqualTo(32);
        assertRejected(
                () -> validator.validateDetails(Map.of("anything", publicKeyShaped)),
                "details.anything",
                Reason.FULL_WALLET);
    }

    @Test
    void aFullWalletAddressAsTheActorIsRefused() {
        String address = TestWallet.generate().address();

        assertRejected(() -> validator.validate(event(address, "corr", null)), "actor", Reason.FULL_WALLET);
    }

    @Test
    void aFullWalletAddressAsTheTargetRefIsRefused() {
        String address = TestWallet.generate().address();

        assertRejected(
                () -> validator.validate(event("system", "corr", "wallet:" + address)),
                "targetRef",
                Reason.FULL_WALLET);
    }

    @Test
    void theMaskedAddressPasses() {
        String masked = com.memetrade.shared.wallet.WalletAddress.mask(
                TestWallet.generate().address());

        assertThatCode(() -> validator.validate(anonymous(Map.of("wallet", masked))))
                .doesNotThrowAnyException();
    }

    @Test
    void legitimateLongValuesPass() {
        String uuidWithoutHyphens = "7c9e6679742540de944be07fc1f90ae7";
        String hexHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        String digestWithAnAddressShapedRun = "0" + "2".repeat(44) + "0" + "ab".repeat(9);
        String inviteCode = com.memetrade.testsupport.TestInvites.newCode();
        String inviteDigest = com.memetrade.testsupport.TestInvites.digest(inviteCode);

        assertThat(digestWithAnAddressShapedRun).hasSize(64);
        assertThat(AuditEventValidator.decodedLength("2".repeat(44))).isEqualTo(32);
        assertThatCode(() -> validator.validateDetails(Map.of(
                        "id", uuidWithoutHyphens,
                        "hash", hexHash,
                        "digest", digestWithAnAddressShapedRun,
                        "code", inviteCode,
                        "codeDigest", inviteDigest,
                        "uuid", UUID.randomUUID().toString())))
                .doesNotThrowAnyException();
    }

    @Test
    void theFullWalletCheckDoesNotHideTheSignatureCheck() {
        String signature = TestWallet.generate().sign("message to sign");

        assertRejected(
                () -> validator.validateDetails(Map.of("proof", signature)), "details.proof", Reason.FORBIDDEN_VALUE);
        assertThat(AuditEventValidator.containsFullWallet(signature)).isFalse();
        assertThat(AuditEventValidator.containsFullWallet(TestWallet.generate().address()))
                .isTrue();
        assertThat(AuditEventValidator.containsFullWallet(null)).isFalse();
        assertThat(AuditEventValidator.containsFullWallet("")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonce", "signature", "token", "accessToken", "REFRESHTOKEN", "Authorization", "password"})
    void everyForbiddenDetailKeyIsRefusedWhateverTheCase(String key) {
        assertRejected(
                () -> validator.validateDetails(Map.of(key, "harmless")), "details." + key, Reason.FORBIDDEN_KEY);
    }

    @Test
    void aJwtInDetailsIsRefused() {
        assertRejected(
                () -> validator.validate(anonymous(Map.of("note", "token was " + jwt()))),
                "details.note",
                Reason.FORBIDDEN_VALUE);
    }

    @Test
    void aJwtInTheTargetRefIsRefused() {
        AuditEventCommand event = new AuditEventCommand(
                UUID.randomUUID(),
                Optional.empty(),
                "system",
                ActorType.SYSTEM,
                "TEST_ACTION",
                "session:" + jwt(),
                "corr",
                NOW,
                Map.of());

        assertRejected(() -> validator.validate(event), "targetRef", Reason.FORBIDDEN_VALUE);
    }

    @Test
    void aBearerCredentialIsRefusedInAnyCase() {
        assertRejected(
                () -> validator.validate(anonymous(Map.of("header", "bearer abc.def"))),
                "details.header",
                Reason.FORBIDDEN_VALUE);
        assertRejected(
                () -> validator.validate(anonymous(Map.of("header", "Bearer opaque-token"))),
                "details.header",
                Reason.FORBIDDEN_VALUE);
    }

    @Test
    void aBase58SignatureOf64BytesIsRefusedButAWalletAddressIsNot() {
        String signature = TestWallet.generate().sign("message to sign");

        assertThat(AuditEventValidator.decodedLength(signature)).isEqualTo(64);
        assertRejected(
                () -> validator.validate(anonymous(Map.of("proof", signature))),
                "details.proof",
                Reason.FORBIDDEN_VALUE);
        assertThat(AuditEventValidator.containsForbiddenValue(
                        TestWallet.generate().address()))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"login_rejected", "1LOGIN", "LOGIN-REJECTED", "LOGIN REJECTED"})
    void anActionOutsideTheUpperCaseFormatIsRefused(String action) {
        assertRejected(() -> validator.validate(withAction(action)), "action", Reason.INVALID_FORMAT);
    }

    @Test
    void anActionHasAtMost64Characters() {
        assertThatCode(() -> validator.validate(withAction("A".repeat(64)))).doesNotThrowAnyException();
        assertRejected(() -> validator.validate(withAction("A".repeat(65))), "action", Reason.INVALID_FORMAT);
    }

    @Test
    void aPersonEventWithoutTenantIsRefused() {
        AuditEventCommand event = new AuditEventCommand(
                UUID.randomUUID(),
                Optional.empty(),
                UUID.randomUUID().toString(),
                ActorType.PERSON,
                AuditActions.LOGOUT,
                null,
                "corr",
                NOW,
                Map.of());

        assertRejected(() -> validator.validate(event), "tenantId", Reason.MISSING_FIELD);
    }

    @Test
    void overlongActorCorrelationAndTargetAreRefused() {
        assertRejected(() -> validator.validate(event("x".repeat(121), "corr", null)), "actor", Reason.INVALID_FORMAT);
        assertRejected(
                () -> validator.validate(event("system", "c".repeat(65), null)),
                "correlationId",
                Reason.INVALID_FORMAT);
        assertRejected(
                () -> validator.validate(event("system", "corr", "t".repeat(201))), "targetRef", Reason.INVALID_FORMAT);
    }

    @Test
    void theRejectionNamesFieldAndReasonButNeverTheValue() {
        String token = jwt();

        assertThatThrownBy(() -> validator.validate(anonymous(Map.of("note", token))))
                .isInstanceOf(AuditPublishRejectedException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("details.note")
                .hasMessageContaining("FORBIDDEN_VALUE")
                .message()
                .doesNotContain(token);
    }

    @Test
    void administratorAndSystemEventsCarryNoTenantAndPass() {
        AuditEventCommand admin = AuditEventCommand.admin(
                UUID.randomUUID(), AuditActions.AUDIT_QUERIED, null, "corr", NOW, Map.of("from", NOW.toString()));
        AuditEventCommand system = AuditEventCommand.system(
                AuditActions.AUDIT_RETENTION_PURGED, null, "corr", NOW, Map.of("deletedCount", "0"));

        assertThat(admin.tenantId()).isEmpty();
        assertThat(admin.actorType()).isEqualTo(ActorType.ADMIN);
        assertThat(system.actor()).isEqualTo("system");
        assertThatCode(() -> validator.validate(admin)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(system)).doesNotThrowAnyException();
    }

    @Test
    void base58LengthCountsLeadingOnesAsZeroBytesAndEmptyTextIsAllowed() {
        assertThat(AuditEventValidator.decodedLength("1112")).isEqualTo(4);
        assertThat(AuditEventValidator.decodedLength("111")).isEqualTo(3);
        assertThat(AuditEventValidator.containsForbiddenValue(null)).isFalse();
        assertThat(AuditEventValidator.containsForbiddenValue("")).isFalse();
        assertThat(AuditEventValidator.isValidAction(null)).isFalse();
    }

    /** A compact JWS built at run time, so no token-shaped literal lives in the repository. */
    static String jwt() {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        return url.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + url.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8))
                + "." + url.encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRejected(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AuditPublishRejectedException.class, rejected -> {
            assertThat(rejected.field()).isEqualTo(field);
            assertThat(rejected.reason()).isEqualTo(reason);
        });
    }

    private static AuditEventCommand anonymous(Map<String, String> details) {
        return AuditEventCommand.anonymous(AuditActions.LOGIN_REJECTED, "AbCd…WxYz", null, "corr", NOW, details);
    }

    private static AuditEventCommand withAction(String action) {
        return AuditEventCommand.anonymous(action, "AbCd…WxYz", null, "corr", NOW, Map.of());
    }

    private static AuditEventCommand event(String actor, String correlationId, String targetRef) {
        return new AuditEventCommand(
                UUID.randomUUID(),
                Optional.empty(),
                actor,
                ActorType.SYSTEM,
                "TEST_ACTION",
                targetRef,
                correlationId,
                NOW,
                Map.of());
    }
}
