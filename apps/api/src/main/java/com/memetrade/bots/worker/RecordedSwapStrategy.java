package com.memetrade.bots.worker;

import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.Factor;
import com.memetrade.decision.domain.NoTradeReason;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.config.RecordedSwapFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U4 (infrastructure-specification.md "Decisão do bot"): the TEST-ONLY strategy of the REAL
 * swap recorded for the local validator. EXISTS ONLY IN THE TEST PROFILE, in the worker, and acts only when the
 * {@link RecordedSwapFixture} exists (the REAL Compose with the recordings) and only on a bot whose selection is
 * exactly the recorded mint (the bots the e2e specs create for the smoke account). For that bot it replaces the
 * decision engine:
 *
 * <ol>
 *   <li>an open position in the mint, in the mode of the bot: HOLD (it never sells; "vender tudo e sacar" does);
 *   <li>a cycle of the bot already recorded its order: NO_TRADE (one buy, ever);
 *   <li>otherwise, for a REAL bot: BUY of exactly {@link RecordedSwapFixture#buyLamports()} of the mint, through the same
 *       barrier (M5) and the same M6 as any REAL bot;
 *   <li>otherwise, for a PAPER bot (261121-paper-real-unico U1, BR10.1: one buy in each mode in the walking skeleton):
 *       BUY of {@link RecordedSwapFixture#expectedOutTokens()} of the mint, through the same barrier of U6 and the same
 *       PAPER execution of U7 as any PAPER bot, at the price sim-a publishes for the mint. The PAPER bot never touches
 *       the REAL ledger, and the REAL bot never the PAPER one.
 * </ol>
 *
 * A buy the barrier refuses (for example before the deposit is credited) records nothing, so the next slot tries again.
 */
@Component
@Profile("test")
@ProcessRole(ProcessRole.Kind.WORKER)
public class RecordedSwapStrategy {

    static final String RECORDED = "REAL_RECORDED";
    /** The outcome of the cycle of a PAPER fill ({@code CycleRunner}): the one PAPER buy is done. */
    static final String PAPER_FILLED = "FILLED";

    private static final Duration SLOT = Duration.ofMinutes(5);

    private final Optional<RecordedSwapFixture> fixture;
    private final JdbcCycleRepository cycles;

    public RecordedSwapStrategy(Optional<RecordedSwapFixture> fixture, JdbcCycleRepository cycles) {
        this.fixture = fixture;
        this.cycles = cycles;
    }

    /** The decision of the test strategy, with the exact lamports of a REAL BUY (empty for PAPER). */
    record Decision(DecisionOutcome outcome, Optional<Long> buyLamports) {}

    /** True for a test bot of the recorded swap: a bot, of either mode, whose selection is only the recorded mint. */
    boolean applies(Bot bot) {
        if (fixture.isEmpty()) {
            return false;
        }
        TokenId recorded = new TokenId("solana", fixture.get().mint());
        return bot.selection().rules().isEmpty() && bot.selection().tokens().equals(List.of(recorded));
    }

    /**
     * @param holdsMint the bot has an open position in the recorded mint, in its own mode (the bot wallet for REAL, the
     *     PAPER account of the bot for PAPER)
     * @param factors the factors the engine recorded for the same inputs, kept for the audit of the decision
     * @return empty when the bot is not a test bot of the recorded swap
     */
    Optional<Decision> decide(Bot bot, boolean holdsMint, Instant slotStart, List<Factor> factors) {
        if (!applies(bot)) {
            return Optional.empty();
        }
        RecordedSwapFixture recorded = fixture.get();
        TokenId token = new TokenId("solana", recorded.mint());
        Instant validUntil = slotStart.plus(SLOT);
        if (holdsMint) {
            return Optional.of(new Decision(
                    new DecisionOutcome(
                            DecisionAction.HOLD,
                            Optional.of(token),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            factors,
                            "Estratégia de teste do swap gravado: mantém a posição até o vender tudo.",
                            validUntil),
                    Optional.empty()));
        }
        if (cycles.hasOutcome(bot.botId(), bot.real() ? RECORDED : PAPER_FILLED)) {
            return Optional.of(new Decision(
                    new DecisionOutcome(
                            DecisionAction.NO_TRADE,
                            Optional.of(token),
                            Optional.of(NoTradeReason.NO_SIGNAL),
                            Optional.empty(),
                            Optional.empty(),
                            factors,
                            "Estratégia de teste do swap gravado: a compra única já foi feita.",
                            validUntil),
                    Optional.empty()));
        }
        if (!bot.real()) {
            return Optional.of(new Decision(
                    new DecisionOutcome(
                            DecisionAction.BUY,
                            Optional.of(token),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(recorded.expectedOutTokens()),
                            factors,
                            "Estratégia de teste do swap gravado: compra simulada única de "
                                    + recorded.expectedOutTokens().toPlainString() + " tokens do mint gravado.",
                            validUntil),
                    Optional.empty()));
        }
        return Optional.of(new Decision(
                new DecisionOutcome(
                        DecisionAction.BUY,
                        Optional.of(token),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(recorded.expectedOutTokens()),
                        factors,
                        "Estratégia de teste do swap gravado: compra única de " + recorded.buyLamports()
                                + " lamports do mint gravado.",
                        validUntil),
                Optional.of(recorded.buyLamports())));
    }
}
