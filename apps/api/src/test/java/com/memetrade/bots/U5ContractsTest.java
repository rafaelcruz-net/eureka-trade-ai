package com.memetrade.bots;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.shared.error.ErrorCode;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 3 of U5: the public names of C4, C16 and the idempotent operations of the bot commands. */
@Tag("u5")
class U5ContractsTest {

    @Test
    void theBotCommandsUseTheCommonIdempotencyMechanismWithTheirOwnOperations() {
        assertThat(IdempotentOperation.BOT_CREATE.operationName()).isEqualTo("bots.create");
        assertThat(IdempotentOperation.BOT_DRAFT_SAVE.operationName()).isEqualTo("bots.draft-save");
        assertThat(IdempotentOperation.BOT_START.operationName()).isEqualTo("bots.start");
        assertThat(IdempotentOperation.BOT_PAUSE.operationName()).isEqualTo("bots.pause");
        assertThat(IdempotentOperation.BOT_RESUME.operationName()).isEqualTo("bots.resume");
        assertThat(IdempotentOperation.BOT_STOP.operationName()).isEqualTo("bots.stop");
        assertThat(IdempotentOperation.BOT_LIMITS.operationName()).isEqualTo("bots.limits");
        assertThat(Arrays.stream(IdempotentOperation.values())
                        .filter(op -> op.name().startsWith("BOT_")))
                .allMatch(op -> op.retention() == IdempotentOperation.Retention.DEFAULT)
                .hasSize(7);
    }

    @Test
    void theStatesAndPauseReasonsAreThoseOfC4WithTheU5Additions() {
        assertThat(BotState.values())
                .extracting(Enum::name)
                .containsExactly("DRAFT", "VALIDATED", "ACTIVE", "PAUSED", "CLOSING_PENDING", "STOPPED");
        assertThat(PauseReason.values())
                .extracting(Enum::name)
                .containsExactly("PERSON", "DAILY_LOSS", "DRAWDOWN", "SYSTEM", "ADMIN_RELEASED", "WAITING_ACTIVE_SLOT");
    }

    @Test
    void theDomainErrorsOfC4HaveTheirStatusAndCode() {
        assertThat(ErrorCode.INVALID_TRANSITION.status()).isEqualTo(409);
        assertThat(ErrorCode.ACTIVE_BOT_LIMIT.status()).isEqualTo(409);
        assertThat(ErrorCode.PAUSED_BY_SYSTEM.status()).isEqualTo(409);
        assertThat(ErrorCode.DAILY_LOSS_LIMIT_REACHED.status()).isEqualTo(409);
        assertThat(ErrorCode.LIMIT_INCOHERENT.status()).isEqualTo(422);
        assertThat(ErrorCode.VIRTUAL_BALANCE_ABOVE_CAP.status()).isEqualTo(422);
        assertThat(ErrorCode.BOT_VALIDATION_FAILED.status()).isEqualTo(422);
        assertThat(ErrorCode.BOT_VALIDATION_FAILED.code()).isEqualTo("VALIDATION_FAILED");
    }
}
