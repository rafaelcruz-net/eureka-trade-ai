package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (C4; BR5.1, BR5.3, NFR1.1, NFR1.9, NFR4.4; ADR-003): the ONLY place that sees the two
 * modes together, and it never computes anything with them. It sends the two blocks to the pool at the same time, each
 * with the same deadline ({@code memetrade.pnl.by-mode.block-timeout}, counted from the submission), waits for each up
 * to that deadline, and puts the results side by side: the worst case of a request is one deadline, never the sum.
 *
 * <p>A block that fails, times out or is refused by a full queue becomes UNAVAILABLE ({@code SERVICE_UNAVAILABLE} and
 * the correlation id read on the request thread), with a {@code WARN} log and one more count of its series; the other
 * block goes on. No exception of a block leaves this class, so the route never answers 500 because of one mode. The
 * view is generic: it never calls arithmetic nor an accessor of a value of either mode (rule NEVER_SUM_MODES).
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class ModePnlView {

    private static final Logger log = LoggerFactory.getLogger(ModePnlView.class);

    static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private final ModeBlockExecutor blocks;
    private final PnlModeMetrics metrics;
    private final Duration blockTimeout;

    public ModePnlView(ModeBlockExecutor blocks, PnlModeMetrics metrics, PnlByModeProperties properties) {
        this.blocks = blocks;
        this.metrics = metrics;
        this.blockTimeout = properties.blockTimeout();
    }

    /** The two blocks of the window ALL, each computed and failing on its own. */
    public <P, R> ModePnlStatement<P, R> statement(Callable<P> paper, Callable<R> real) {
        String correlationId = CorrelationId.current();
        long deadline = System.nanoTime() + blockTimeout.toNanos();
        CompletableFuture<P> paperBlock = blocks.submit(PnlMarket.PAPER, deadline, paper);
        CompletableFuture<R> realBlock = blocks.submit(PnlMarket.REAL, deadline, real);
        return new ModePnlStatement<>(
                ModePnlStatement.ALL,
                result(paperBlock, PnlMarket.PAPER, "USD", deadline, correlationId),
                result(realBlock, PnlMarket.REAL, "SOL", deadline, correlationId));
    }

    private <T> BlockResult<T> result(
            CompletableFuture<T> block, PnlMarket market, String currency, long deadline, String correlationId) {
        String label;
        PnlModeMetrics.Cause cause;
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            return new BlockResult.Available<>(block.get(left, TimeUnit.NANOSECONDS));
        } catch (TimeoutException late) {
            block.cancel(false);
            label = "TIMEOUT";
            cause = PnlModeMetrics.Cause.TIMEOUT;
        } catch (ExecutionException failed) {
            label = labelOf(failed.getCause());
            cause = causeOf(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            label = "INTERRUPTED";
            cause = PnlModeMetrics.Cause.ERROR;
        }
        log.warn("P&L block unavailable: market={}, cause={}", market, label);
        metrics.unavailable(market, cause);
        String code = cause == PnlModeMetrics.Cause.RECONCILIATION_MISMATCH
                ? RealPnlUnavailable.RECONCILIATION_MISMATCH
                : SERVICE_UNAVAILABLE;
        return new BlockResult.Unavailable<>(market, currency, code, correlationId);
    }

    /** A closed value or the simple name of the class of the failure; never its message (it may carry data). */
    private static String labelOf(Throwable failure) {
        if (failure instanceof RealPnlUnavailable unavailable) {
            return unavailable.causeLabel();
        }
        PnlModeMetrics.Cause cause = causeOf(failure);
        if (cause != PnlModeMetrics.Cause.ERROR) {
            return cause.name();
        }
        return failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
    }

    /**
     * 261121-paper-real-unico U2 (NFR1.6, R-03): the label {@code cause}: {@code TIMEOUT} for any deadline of the block
     * (the queue, the transaction, the attribution, a {@code 57014} or the deadline at a row), {@code REJECTED} for a
     * full queue, the closed causes of {@link RealPnlUnavailable}, {@code ERROR} for anything else.
     */
    static PnlModeMetrics.Cause causeOf(Throwable failure) {
        if (failure instanceof ModeBlockExecutor.Rejected) {
            return PnlModeMetrics.Cause.REJECTED;
        }
        if (failure instanceof ModeBlockExecutor.DeadlineExpired) {
            return PnlModeMetrics.Cause.TIMEOUT;
        }
        if (failure instanceof RealPnlUnavailable unavailable) {
            return switch (unavailable.causeLabel()) {
                case RealPnlUnavailable.TIMEOUT -> PnlModeMetrics.Cause.TIMEOUT;
                case RealPnlUnavailable.RECONCILIATION_MISMATCH -> PnlModeMetrics.Cause.RECONCILIATION_MISMATCH;
                case RealPnlUnavailable.TOO_MANY_ORDERS -> PnlModeMetrics.Cause.TOO_MANY_ORDERS;
                default ->
                    com.memetrade.shared.jdbc.ReadDeadline.isTimeout(unavailable)
                            ? PnlModeMetrics.Cause.TIMEOUT
                            : PnlModeMetrics.Cause.ERROR;
            };
        }
        return failure != null && com.memetrade.shared.jdbc.ReadDeadline.isTimeout(failure)
                ? PnlModeMetrics.Cause.TIMEOUT
                : PnlModeMetrics.Cause.ERROR;
    }
}
