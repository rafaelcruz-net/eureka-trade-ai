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
 * {@link RecordedSwapFixture} exists (the REAL Compose with the recordings) and only on a REAL bot whose selection is
 * exactly the recorded mint (the bot the swap spec creates for the smoke account). For that bot it replaces the decision
 * engine:
 *
 * <ol>
 *   <li>an open REAL position in the mint: HOLD (it never sells; "vender tudo e sacar" does);
 *   <li>a cycle of the bot already recorded a REAL order: NO_TRADE (one buy, ever);
 *   <li>otherwise: BUY of exactly {@link RecordedSwapFixture#buyLamports()} of the mint, through the same barrier (M5)
 *       and the same M6 as any REAL bot.
 * </ol>
 *
 * A buy the barrier refuses (for example before the deposit is credited) records nothing, so the next slot tries again.
 */
@Component
@Profile("test")
@ProcessRole(ProcessRole.Kind.WORKER)
public class RecordedSwapStrategy {

    static final String RECORDED = "REAL_RECORDED";
    private static final Duration SLOT = Duration.ofMinutes(5);

    private final Optional<RecordedSwapFixture> fixture;
    private final JdbcCycleRepository cycles;

    public RecordedSwapStrategy(Optional<RecordedSwapFixture> fixture, JdbcCycleRepository cycles) {
        this.fixture = fixture;
        this.cycles = cycles;
    }

    /** The decision of the test strategy, with the exact lamports of a BUY. */
    record Decision(DecisionOutcome outcome, Optional<Long> buyLamports) {}

    /** True for the test bot of the recorded swap: a REAL bot whose selection is only the recorded mint. */
    boolean applies(Bot bot) {
        if (fixture.isEmpty() || !bot.real()) {
            return false;
        }
        TokenId recorded = new TokenId("solana", fixture.get().mint());
        return bot.selection().rules().isEmpty() && bot.selection().tokens().equals(List.of(recorded));
    }

    /**
     * @param holdsMint the bot wallet has an open REAL position in the recorded mint
     * @param factors the factors the engine recorded for the same inputs, kept for the audit of the decision
     * @return empty when the bot is not the test bot of the recorded swap
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
        if (cycles.hasOutcome(bot.botId(), RECORDED)) {
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
